#!/usr/bin/env bash
# Linux lifecycle launcher. Requires Bash 4.4+, flock, ss, and a JDK 21+.
set -u
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P) || exit 1
cd -- "$SCRIPT_DIR" || exit 1

# Optional overrides; otherwise use JAVA_HOME, then the system PATH.
JAVA_BIN="${NIO_TUNNEL_JAVA:-}"
JAR_PATH="${NIO_TUNNEL_JAR:-$SCRIPT_DIR/nio-tunnel.jar}"
# JAVA_BIN="/opt/jdk-21/bin/java"
# JAR_PATH="/opt/nio-tunnel/nio-tunnel.jar"
PID_FILE="$SCRIPT_DIR/nio-tunnel.pid"
STATE_FILE="$PID_FILE.meta"
OUT_LOG="$SCRIPT_DIR/logs/nio-tunnel.log"
ERROR_LOG="$SCRIPT_DIR/logs/nio-tunnel-error.log"
ACTION=restart
if [[ $# -gt 0 && $1 != --* ]]; then ACTION=${1,,}; shift; fi
# Keep access records in their configured file; explicit CLI options can override this default.
APP_ARGS=(--access.log.console=false "$@")
OWNED_PID=
LOCKED=false

wait_console() {
    if (( $1 > 0 )); then
        printf 'Closing in %s seconds. Press any key to exit now.\n' "$1"
        if [[ -t 0 ]]; then read -r -s -n 1 -t "$1" || true; else sleep "$1"; fi
    else
        printf 'Press any key to exit.\n'
        if [[ -t 0 ]]; then read -r -s -n 1 || true; fi
    fi
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    if $LOCKED; then flock -u 9; fi
    wait_console 0
    exit 1
}

clear_state() { rm -f -- "$PID_FILE" "$STATE_FILE"; }

process_stamp() {
    local stat_text
    local -a fields
    stat_text=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    # The command field may contain spaces or parentheses; fields after it are numeric.
    read -r -a fields <<< "${stat_text##*) }"
    [[ ${fields[0]} != Z ]] || return 1
    printf '%s:%s\n' "$(cat /proc/sys/kernel/random/boot_id)" "${fields[19]}"
}

get_owned_process() {
    OWNED_PID=
    [[ -f $PID_FILE ]] || return 0
    local candidate stamp executable index matched=false
    local -a state argv
    candidate=$(cat -- "$PID_FILE") || return 1
    [[ $candidate =~ ^[1-9][0-9]*$ ]] || { printf 'Invalid PID file.\n' >&2; return 1; }
    if [[ ! -d /proc/$candidate ]]; then
        printf 'Removing stale PID file.\n'; clear_state; return 0
    fi
    [[ -r $STATE_FILE ]] || { printf 'Process identity file is missing. Refusing to stop an unidentified process.\n' >&2; return 1; }
    mapfile -t state < "$STATE_FILE"
    [[ ${#state[@]} -eq 3 ]] || { printf 'Invalid process identity file.\n' >&2; return 1; }
    stamp=$(process_stamp "$candidate") || {
        printf 'Removing stale PID file (process has exited).\n'; clear_state; return 0
    }
    if [[ $stamp != "${state[0]}" ]]; then
        printf 'Removing stale PID file (PID was reused).\n'; clear_state; return 0
    fi
    executable=$(readlink "/proc/$candidate/exe") || return 1
    mapfile -d '' -t argv < "/proc/$candidate/cmdline"
    for ((index=0; index+1<${#argv[@]}; index++)); do
        if [[ ${argv[index]} == -jar && ${argv[index+1]} == "${state[2]}" ]]; then matched=true; break; fi
    done
    if [[ $executable != "${state[1]}" ]] || ! $matched; then
        printf 'Cannot verify process identity for PID %s. Refusing to stop it.\n' "$candidate" >&2
        return 1
    fi
    OWNED_PID=$candidate
}

stop_tunnel() {
    get_owned_process || return 1
    if [[ -z $OWNED_PID ]]; then printf 'nio-tunnel is not running. Continuing.\n'; return 0; fi
    printf 'Stopping nio-tunnel (PID %s)...\n' "$OWNED_PID"
    # SIGTERM allows Java shutdown hooks to run; never escalate silently to SIGKILL.
    kill -TERM "$OWNED_PID" || return 1
    local expected_stamp stamp deadline=$((SECONDS + 15))
    expected_stamp=$(head -n 1 -- "$STATE_FILE")
    while (( SECONDS < deadline )); do
        stamp=$(process_stamp "$OWNED_PID") || { clear_state; printf 'nio-tunnel stopped.\n'; return 0; }
        [[ $stamp == "$expected_stamp" ]] || { clear_state; printf 'nio-tunnel stopped.\n'; return 0; }
        sleep 0.2
    done
    printf 'Stop timed out after 15 seconds. Restart cancelled.\n' >&2
    return 1
}

get_listeners() {
    ss -H -ltnp "( sport = :$HTTP_PORT or sport = :$SOCKS_PORT )"
}

conflicting_address() {
    local existing=$1 requested=$BIND_ADDRESS
    # Normalize the brackets used by ss around IPv6 addresses.
    existing=${existing#[}; existing=${existing%]}
    existing=${existing#::ffff:}
    [[ $existing == "$requested" || $existing == '*' || $existing == '::' || $requested == '::' ]] && return 0
    if [[ $existing != *:* && $requested != *:* ]]; then
        [[ $existing == '0.0.0.0' || $requested == '0.0.0.0' ]] && return 0
    fi
    return 1
}

start_tunnel() {
    get_owned_process || return 1
    if [[ -n $OWNED_PID ]]; then
        printf 'nio-tunnel is already running (PID %s). No new process started.\n' "$OWNED_PID"
        return 0
    fi
    command -v ss >/dev/null || { printf 'Missing ss. Install the iproute2 package.\n' >&2; return 1; }
    [[ -f $JAR_PATH ]] || { printf 'JAR not found: %s. Set JAR_PATH or copy the runnable JAR beside startup.sh.\n' "$JAR_PATH" >&2; return 1; }
    JAR_PATH=$(readlink -f -- "$JAR_PATH") || return 1
    if [[ -z $JAVA_BIN ]]; then
        if [[ -n ${JAVA_HOME:-} ]]; then JAVA_BIN="$JAVA_HOME/bin/java"
        else JAVA_BIN=$(command -v java) || { printf 'Java not found on PATH. Set JAVA_HOME or JAVA_BIN.\n' >&2; return 1; }; fi
    fi
    [[ -x $JAVA_BIN ]] || { printf 'Java executable not found: %s\n' "$JAVA_BIN" >&2; return 1; }
    local config key value listeners line endpoint address owner name executable conflicts=false
    config=$("$JAVA_BIN" "$SCRIPT_DIR/StartupConfig.java" "$JAR_PATH" "${APP_ARGS[@]}") || {
        printf 'Unable to resolve configuration. A JDK 21 or newer and a runnable fat JAR are required.\n' >&2; return 1
    }
    # Read data as strings, never as executable shell assignments.
    while IFS='=' read -r key value; do
        case $key in
            LISTEN_HOST) LISTEN_HOST=$value ;; BIND_ADDRESS) BIND_ADDRESS=$value ;;
            HTTP_PORT) HTTP_PORT=$value ;; SOCKS_PORT) SOCKS_PORT=$value ;;
            UPSTREAM_HOST) UPSTREAM_HOST=$value ;; UPSTREAM_PORT) UPSTREAM_PORT=$value ;;
            ACCESS_LOG) ACCESS_LOG=$value ;;
        esac
    done <<< "$config"
    listeners=$(get_listeners) || { printf 'Unable to query TCP listeners.\n' >&2; return 1; }
    while IFS= read -r line; do
        [[ -n $line ]] || continue
        endpoint=$(awk '{print $4}' <<< "$line")
        address=${endpoint%:*}
        conflicting_address "$address" || continue
        conflicts=true
        printf 'Port conflict: %s\n' "$endpoint"
        local owners
        owners=$(grep -oE 'pid=[0-9]+' <<< "$line" | cut -d= -f2 | sort -u)
        if [[ -z $owners ]]; then
            printf 'PID: Unavailable (permission denied) | Process: Unavailable | Path: Unavailable\n'
        fi
        while IFS= read -r owner; do
            [[ -n $owner ]] || continue
            name=$(cat "/proc/$owner/comm" 2>/dev/null) || name='Unavailable (permission denied)'
            executable=$(readlink "/proc/$owner/exe" 2>/dev/null) || executable='Unavailable (permission denied)'
            printf 'PID: %s | Process: %s | Path: %s\n' "$owner" "$name" "$executable"
        done <<< "$owners"
    done <<< "$listeners"
    if $conflicts; then printf 'Required listener ports are occupied. No process was stopped.\n' >&2; return 1; fi
    mkdir -p -- "$SCRIPT_DIR/logs" || return 1
    printf 'Starting nio-tunnel...\n'
    # Do not let the daemon inherit the lifecycle lock or any console handles.
    # Send SLF4J runtime messages to stdout, which is captured in nio-tunnel.log.
    nohup "$JAVA_BIN" -Dorg.slf4j.simpleLogger.logFile=System.out -jar "$JAR_PATH" "${APP_ARGS[@]}" > "$OUT_LOG" 2> "$ERROR_LOG" < /dev/null 9>&- &
    local child=$! stamp deadline=$((SECONDS + 20)) ready=false http_ready socks_ready
    # Wait for nohup to exec Java before recording its executable and command line.
    while (( SECONDS < deadline )); do
        executable=$(readlink "/proc/$child/exe" 2>/dev/null) || break
        if [[ $executable == "$(readlink -f -- "$JAVA_BIN")" ]]; then break; fi
        sleep 0.1
    done
    stamp=$(process_stamp "$child") || { printf 'Java exited before its identity could be recorded. Check %s\n' "$ERROR_LOG" >&2; return 1; }
    if ! printf '%s\n%s\n%s\n' "$stamp" "$executable" "$JAR_PATH" > "$STATE_FILE" ||
            ! printf '%s\n' "$child" > "$PID_FILE"; then
        kill -TERM "$child" 2>/dev/null || true
        local cleanup_deadline=$((SECONDS + 15))
        while (( SECONDS < cleanup_deadline )) && process_stamp "$child" >/dev/null; do sleep 0.2; done
        if process_stamp "$child" >/dev/null; then
            printf 'Unable to write process identity files. Cleanup timed out; inspect PID %s.\n' "$child" >&2
        else
            clear_state; printf 'Unable to write process identity files. The new process was stopped.\n' >&2
        fi
        return 1
    fi
    while (( SECONDS < deadline )); do
        process_stamp "$child" >/dev/null || break
        listeners=$(get_listeners) || break
        http_ready=false; socks_ready=false
        while IFS= read -r line; do
            [[ $line =~ pid=$child, ]] || continue
            endpoint=$(awk '{print $4}' <<< "$line")
            [[ ${endpoint##*:} == "$HTTP_PORT" ]] && http_ready=true
            [[ ${endpoint##*:} == "$SOCKS_PORT" ]] && socks_ready=true
        done <<< "$listeners"
        if $http_ready && $socks_ready; then ready=true; break; fi
        sleep 0.25
    done
    if ! $ready; then
        printf 'Startup failed before both listener ports became ready. Check %s and %s\n' "$OUT_LOG" "$ERROR_LOG" >&2
        stop_tunnel || return 1
        return 1
    fi
    printf 'nio-tunnel started. PID: %s\nHTTP: %s:%s\nSOCKS: %s:%s\nUpstream: %s:%s\n' \
        "$child" "$LISTEN_HOST" "$HTTP_PORT" "$LISTEN_HOST" "$SOCKS_PORT" "$UPSTREAM_HOST" "$UPSTREAM_PORT"
    printf 'Runtime log: %s\nError log: %s\nAccess log: %s\n' "$OUT_LOG" "$ERROR_LOG" "$ACCESS_LOG"
}

case $ACTION in start|stop|restart) ;; *) fail 'Usage: startup.sh [start|stop|restart] [--config=PATH] [--key=value ...]' ;; esac
for argument in "${APP_ARGS[@]}"; do
    [[ $argument != --help ]] || fail 'Usage: startup.sh [start|stop|restart] [--config=PATH] [--key=value ...]'
done
[[ -d /proc ]] || fail 'This launcher requires Linux /proc.'
command -v flock >/dev/null || fail 'Missing flock. Install the util-linux package.'
# Lock the entire lifecycle operation, including the stop/start pair.
exec 9> "$SCRIPT_DIR/.startup.lock" || fail 'The script directory is not writable.'
flock -n 9 || fail 'Another lifecycle operation is running.'
LOCKED=true
if [[ $ACTION == stop || $ACTION == restart ]]; then stop_tunnel || fail 'Stop failed.'; fi
if [[ $ACTION == start || $ACTION == restart ]]; then start_tunnel || fail 'Start failed.'; fi
flock -u 9
LOCKED=false
wait_console 5
