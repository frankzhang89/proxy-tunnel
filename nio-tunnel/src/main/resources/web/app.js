'use strict';
const $ = id => document.getElementById(id);
const unitNames = ['B', 'KiB', 'MiB', 'GiB'];
const validUnits = ['Auto', ...unitNames];
const unitStorageKey = 'nio-tunnel.displayUnit';
let savedUnit = 'Auto';
try {
    const saved = localStorage.getItem(unitStorageKey);
    if (validUnits.includes(saved)) savedUnit = saved;
} catch (_) {
}
const defaultQuery = {
    scope: 'active',
    q: '',
    route: 'all',
    protocol: 'all',
    page: 1,
    pageSize: 50,
    sort: '',
    order: 'asc'
};
const state = {
    query: {...defaultQuery},
    confirmed: {...defaultQuery},
    paused: false,
    busy: false,
    selected: null,
    pages: 1,
    last: null,
    unit: savedUnit,
    requestId: 0,
    request: null
};
const columnDefinitions = [
    ['processName', 'Process Name', 180], ['pid', 'PID', 80], ['sourceIp', 'Source IP / Port', 200],
    ['protocol', 'Protocol', 130], ['target', 'Target / Port', 230], ['route', 'Route', 100],
    ['uploadBps', 'Upload Speed', 140], ['downloadBps', 'Download Speed', 150], ['status', 'Status', 110]
];
const columnWidths = columnDefinitions.map(c => c[2]);
const numericColumns = new Set(['pid', 'uploadBps', 'downloadBps']);

function applyWidths() {
    [...$('columns').children].forEach((col, i) => {
        col.style.width = columnWidths[i] + 'px';
    });
    $('connections').style.width = columnWidths.reduce((a, b) => a + b, 0) + 'px';
    [...$('column-headings').children].forEach((th, i) => th.querySelector('.resize-handle').setAttribute('aria-valuenow', columnWidths[i]));
}

function sortIndicators() {
    for (const row of $('rows').children) [...row.children].forEach((td, i) => td.classList.toggle('sorted-column', columnDefinitions[i][0] === state.query.sort));
    [...$('column-headings').children].forEach((th, i) => {
        const selected = columnDefinitions[i][0] === state.query.sort;
        th.setAttribute('aria-sort', selected ? (state.query.order === 'asc' ? 'ascending' : 'descending') : 'none');
        th.querySelector('.sort-arrow').textContent = selected ? (state.query.order === 'asc' ? '▴' : '▾') : '';
    });
}

function initializeColumns() {
    columnWidths[4] += Math.max(0, $('connections').parentElement.clientWidth - 1320);
    columnDefinitions.forEach(([key, label], index) => {
        $('columns').append(document.createElement('col'));
        const th = node('th', null, numericColumns.has(key) ? 'numeric' : '');
        th.scope = 'col';
        th.dataset.sort = key;
        const button = node('button', null, 'sort-header');
        button.type = 'button';
        button.title = 'Sort by ' + label;
        button.append(node('span', label, 'column-label'), node('span', '', 'sort-arrow'));
        button.querySelector('.sort-arrow').setAttribute('aria-hidden', 'true');
        button.onclick = () => setQuery({
            sort: key,
            order: state.query.sort === key ? (state.query.order === 'asc' ? 'desc' : 'asc') : (key.endsWith('Bps') ? 'desc' : 'asc'),
            page: 1
        });
        const handle = node('span', '', 'resize-handle');
        handle.tabIndex = 0;
        handle.setAttribute('role', 'separator');
        handle.setAttribute('aria-orientation', 'vertical');
        handle.setAttribute('aria-label', 'Resize ' + label + ' column');
        handle.setAttribute('aria-valuemin', '48');
        handle.onclick = event => {
            event.preventDefault();
            event.stopPropagation();
        };
        handle.onkeydown = event => {
            if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
                event.preventDefault();
                columnWidths[index] = Math.max(48, columnWidths[index] + (event.key === 'ArrowRight' ? 10 : -10));
                applyWidths();
            }
        };
        handle.onpointerdown = event => {
            if (event.button !== 0) return;
            event.preventDefault();
            event.stopPropagation();
            const x = event.clientX, initialWidth = columnWidths[index];
            handle.setPointerCapture(event.pointerId);
            document.body.classList.add('resizing-columns');
            handle.onpointermove = move => {
                columnWidths[index] = Math.max(48, initialWidth + move.clientX - x);
                applyWidths();
            };
            const end = () => {
                handle.onpointermove = null;
                document.body.classList.remove('resizing-columns');
            };
            handle.onpointerup = up => {
                up.preventDefault();
                up.stopPropagation();
                end();
                if (handle.hasPointerCapture(up.pointerId)) handle.releasePointerCapture(up.pointerId);
            };
            handle.onpointercancel = end;
            handle.onlostpointercapture = end;
        };
        th.append(button, handle);
        $('column-headings').append(th);
    });
    applyWidths();
    sortIndicators();
}

const statusNames = {connecting: 'Connecting', active: 'Active', closed: 'Closed', failed: 'Failed'};
const protocolNames = {
    http: 'HTTP',
    http_connect: 'HTTP CONNECT',
    socks4: 'SOCKS4/4a',
    socks5: 'SOCKS5',
    websocket: 'WebSocket'
};

function bytes(n, speed = false) {
    let index = state.unit === 'Auto' ? 0 : unitNames.indexOf(state.unit);
    if (state.unit === 'Auto') while (n / 1024 ** index >= 1024 && index < unitNames.length - 1) index++;
    return `${(n / 1024 ** index).toFixed(index ? 2 : 0)} ${unitNames[index]}${speed ? '/s' : ''}`;
}

function endpoint(host, port) {
    return `${host.includes(':') ? '[' + host + ']' : host}:${port}`;
}

function dateTime(value) {
    return new Date(value).toLocaleString('en-US');
}

function node(tag, text, cls) {
    const n = document.createElement(tag);
    if (text != null) n.textContent = text;
    if (cls) n.className = cls;
    return n;
}

function processName(p) {
    return p.status === 'remote' ? 'Remote client' : p.name || (p.status === 'pending' ? 'Resolving' : 'Unknown');
}

function cell(row, text, secondary, cls) {
    const td = node('td', null, cls);
    const content = node('span', text, 'cell-text');
    content.title = text ?? '';
    td.append(content);
    if (secondary) td.append(node('small', secondary));
    row.append(td);
    return td;
}

function detail(r) {
    $('detail').hidden = !r;
    if (!r) return;
    const box = $('detail');
    box.replaceChildren(node('strong', `Connection ${r.id} · ${processName(r.process)}`));
    for (const text of [
        `Source ${endpoint(r.source.host, r.source.port)} (${r.process.status === 'remote' ? 'Remote' : 'Local'}) → Listener ${endpoint(r.listener.host, r.listener.port)}`,
        `Target ${endpoint(r.targetHost, r.targetPort)} · Route ${r.route}`,
        `Uploaded ${bytes(r.uploadBytes)} · Downloaded ${bytes(r.downloadBytes)}`,
        `Started ${dateTime(r.startedAt)} · Ended ${r.endedAt ? dateTime(r.endedAt) : '—'}`,
        `Process PID ${r.process.pid ?? '—'} · ${r.process.reason || 'Resolved'}${r.reason ? ' · Result: ' + r.reason : ''}`
    ]) box.append(node('div', text));
}

function render(data) {
    state.last = data;
    const s = data.summary;
    $('active').textContent = `${s.activeClients} / ${s.activeFlows}`;
    $('routes').textContent = `upstream ${s.upstreamFlows} · direct ${s.directFlows}`;
    $('upload').textContent = bytes(s.uploadBps, true);
    $('download').textContent = bytes(s.downloadBps, true);
    $('total').textContent = `↑ ${bytes(s.uploadBytes)}  ↓ ${bytes(s.downloadBytes)}`;
    $('ports').textContent = `PID ${data.serverPid ?? '—'} / HTTP :${data.httpPort} / SOCKS :${data.socksPort}`;
    $('upstream').textContent = data.upstream ? `Upstream ${endpoint(data.upstream.host, data.upstream.port)}` : 'Upstream —';
    $('process').textContent = `Process lookup: ${data.processStatus}`;
    const rows = $('rows'), existing = new Map([...rows.children].map(n => [n.dataset.id, n]));
    for (const r of data.connections) {
        const tr = existing.get(r.id) || node('tr');
        existing.delete(r.id);
        tr.dataset.id = r.id;
        tr.className = state.selected === r.id ? 'chosen' : '';
        tr.replaceChildren();
        cell(tr, processName(r.process));
        cell(tr, r.process.pid ?? '—', null, 'numeric');
        cell(tr, endpoint(r.source.host, r.source.port));
        cell(tr, protocolNames[r.protocol] || r.protocol);
        cell(tr, endpoint(r.targetHost, r.targetPort));
        cell(tr, '').replaceChildren(Object.assign(node('span', r.route, `cell-text badge ${r.route}`), {title: r.route}));
        cell(tr, bytes(r.uploadBps, true), null, 'up numeric');
        cell(tr, bytes(r.downloadBps, true), null, 'down numeric');
        cell(tr, '').replaceChildren(Object.assign(node('span', statusNames[r.state] || r.state, `cell-text badge ${r.state}`), {title: statusNames[r.state] || r.state}));
        tr.onclick = () => {
            state.selected = r.id;
            render(state.last);
        };
        rows.append(tr);
    }
    for (const tr of existing.values()) tr.remove();
    $('empty').hidden = !!data.connections.length;
    state.pages = data.pagination.pages;
    $('count').textContent = `${data.pagination.total} connection${data.pagination.total === 1 ? '' : 's'}`;
    $('page').textContent = `${data.pagination.page} / ${state.pages}`;
    $('prev').disabled = data.pagination.page <= 1;
    $('next').disabled = data.pagination.page >= state.pages;
    sortIndicators();
    detail(data.connections.find(r => r.id === state.selected));
}

const refreshStorageKey = 'nio-tunnel.refreshIntervalSeconds';
const maxTimerDelay = 2147483647n;
let refreshSeconds = 1n;
try {
    refreshSeconds = parseRefreshSeconds(localStorage.getItem(refreshStorageKey)) || refreshSeconds;
} catch (_) {
}
let failures = 0, nextAttempt = 0, refreshTimer;
let refreshStatus = 'connecting';

function parseRefreshSeconds(value) {
    return value && !/[^0-9]/.test(value) ? BigInt(value) : 0n;
}

function showRefreshStatus(status = refreshStatus) {
    refreshStatus = status;
    const time = state.last ? new Date(state.last.sampleTime).toLocaleTimeString('en-US') : '—';
    const text = {
        connecting: `Connecting… · Every ${refreshSeconds} s`,
        live: `● Updates every ${refreshSeconds} s · ${time}`,
        paused: `Ⅱ Page updates paused · Every ${refreshSeconds} s · Last update ${time}`,
        error: `Updates interrupted · Every ${refreshSeconds} s · Last update ${time}`
    };
    $('refresh').classList.toggle('error', status === 'error');
    $('refresh').textContent = text[status];
}

function scheduleRefresh() {
    clearTimeout(refreshTimer);
    if (state.paused || document.hidden) return;

    const interval = refreshSeconds * 1000n;
    const backoff = BigInt(Math.max(0, nextAttempt - Date.now()));
    const deadline = BigInt(Math.floor(performance.now())) + (interval > backoff ? interval : backoff);
    function waitUntilDue() {
        const remaining = deadline - BigInt(Math.floor(performance.now()));
        if (remaining > 0n) {
            refreshTimer = setTimeout(waitUntilDue, Number(remaining > maxTimerDelay ? maxTimerDelay : remaining));
            return;
        }
        scheduleRefresh();
        refresh();
    }
    waitUntilDue();
}

function initializeRefreshControl() {
    const control = $('refresh-control');
    const input = $('refresh-seconds');
    const list = $('refresh-options');
    const toggle = $('refresh-options-toggle');
    const error = $('refresh-input-error');
    const options = [...list.children];
    let activeIndex = -1;

    function showOptions(open, index = -1) {
        activeIndex = index;
        list.hidden = !open;
        input.setAttribute('aria-expanded', open);
        toggle.setAttribute('aria-expanded', open);
        toggle.setAttribute('aria-label', open ? 'Hide refresh intervals' : 'Show refresh intervals');
        for (const [i, option] of options.entries()) option.setAttribute('aria-selected', i === index);
        if (index < 0) input.removeAttribute('aria-activedescendant');
        else input.setAttribute('aria-activedescendant', options[index].id);
    }

    function showInputError(invalid) {
        if (invalid) input.setAttribute('aria-invalid', 'true');
        else input.removeAttribute('aria-invalid');
        error.textContent = invalid ? 'Enter a positive whole number of seconds.' : '';
    }

    function commit(value) {
        showOptions(false);
        const seconds = parseRefreshSeconds(value);
        showInputError(!seconds);
        if (!seconds) return;
        input.value = String(seconds);
        if (seconds === refreshSeconds) return;
        refreshSeconds = seconds;
        try {
            localStorage.setItem(refreshStorageKey, input.value);
        } catch (_) {
        }
        showRefreshStatus();
        scheduleRefresh();
    }

    input.value = String(refreshSeconds);
    input.oninput = () => {
        showOptions(false);
        showInputError(false);
    };
    input.onkeydown = event => {
        if (event.isComposing) return;
        switch (event.key) {
            case 'ArrowDown':
            case 'ArrowUp': {
                event.preventDefault();
                const step = event.key === 'ArrowDown' ? 1 : -1;
                const start = activeIndex < 0 && step < 0 ? 0 : activeIndex;
                showOptions(true, (start + step + options.length) % options.length);
                break;
            }
            case 'Enter':
                event.preventDefault();
                commit(activeIndex < 0 ? input.value : options[activeIndex].dataset.seconds);
                break;
            case 'Escape':
                event.preventDefault();
                input.value = String(refreshSeconds);
                showInputError(false);
                showOptions(false);
                break;
        }
    };

    // Keep the field focused until a pointer selection has been applied.
    toggle.onmousedown = list.onmousedown = event => event.preventDefault();
    toggle.onclick = () => {
        input.focus();
        showOptions(list.hidden);
    };
    list.onclick = event => {
        const option = event.target.closest('[role="option"]');
        if (option) commit(option.dataset.seconds);
    };
    control.addEventListener('focusout', event => {
        if (!control.contains(event.relatedTarget)) commit(input.value);
    });
}

function syncControls(restoreSearch = false) {
    $('page-size').value = state.query.pageSize;
    $('route').value = state.query.route;
    $('protocol').value = state.query.protocol;
    if (restoreSearch) $('search').value = state.query.q;
    for (const button of $('tabs').children) button.classList.toggle('selected', button.dataset.scope === state.query.scope);
    sortIndicators();
}

async function refresh(manual = false) {
    if (!manual && (state.busy || state.paused || document.hidden || Date.now() < nextAttempt)) return;
    if (state.request) state.request.controller.abort();
    const id = ++state.requestId, controller = new AbortController(), requested = {...state.query};
    state.request = {id, controller, manual};
    state.busy = true;
    $('connections').setAttribute('aria-busy', 'true');
    const timer = setTimeout(() => controller.abort(), 5000);
    try {
        const query = new URLSearchParams(requested);
        if (!requested.sort) query.delete('sort');
        const response = await fetch('/api/snapshot?' + query, {signal: controller.signal});
        if (!response.ok) throw Error(response.status);
        const data = await response.json();
        if (id !== state.requestId || (state.paused && !manual)) return;
        state.query = {...requested, page: data.pagination.page, pageSize: data.pagination.pageSize};
        state.confirmed = {...state.query};
        syncControls();
        render(data);
        failures = 0;
        showRefreshStatus(state.paused ? 'paused' : 'live');
    } catch (error) {
        if (id !== state.requestId) return;
        state.query = {...state.confirmed};
        syncControls(true);
        if (state.last) render(state.last);
        failures++;
        nextAttempt = Date.now() + Math.min(10000, 1000 * 2 ** failures);
        showRefreshStatus('error');
        scheduleRefresh();
    } finally {
        clearTimeout(timer);
        if (id === state.requestId) {
            state.busy = false;
            state.request = null;
            $('connections').setAttribute('aria-busy', 'false');
        }
    }
}

function setQuery(patch) {
    state.query = {...state.query, ...patch};
    nextAttempt = 0;
    syncControls();
    scheduleRefresh();
    refresh(true);
}

$('address').textContent = location.host;
initializeColumns();
initializeRefreshControl();
showRefreshStatus();
$('units').value = state.unit;
$('units').onchange = () => {
    state.unit = validUnits.includes($('units').value) ? $('units').value : 'Auto';
    try {
        localStorage.setItem(unitStorageKey, state.unit);
    } catch (_) {
    }
    if (state.last) render(state.last);
};
for (const button of $('tabs').children) button.onclick = () => setQuery({scope: button.dataset.scope, page: 1});
for (const id of ['route', 'protocol']) $(id).onchange = () => setQuery({[id]: $(id).value, page: 1});
$('page-size').onchange = () => setQuery({pageSize: Number($('page-size').value), page: 1});
let debounce;
$('search').oninput = () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => setQuery({q: $('search').value, page: 1}), 250);
};
$('pause').onclick = () => {
    state.paused = !state.paused;
    $('pause').textContent = state.paused ? '▶ Resume refresh' : 'Ⅱ Pause refresh';
    if (state.paused) {
        if (state.request && !state.request.manual) {
            ++state.requestId;
            state.request.controller.abort();
            state.request = null;
            state.busy = false;
            $('connections').setAttribute('aria-busy', 'false');
        }
        showRefreshStatus('paused');
    } else {
        nextAttempt = 0;
        refresh();
    }
    scheduleRefresh();
};
$('prev').onclick = () => setQuery({page: state.query.page - 1});
$('next').onclick = () => setQuery({page: state.query.page + 1});
document.addEventListener('visibilitychange', () => {
    if (!document.hidden) {
        nextAttempt = 0;
        refresh();
    }
    scheduleRefresh();
});
scheduleRefresh();
refresh();
