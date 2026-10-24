#!/usr/bin/env python3
"""Package Maven Shade outputs and runtime companions; publish ZIPs only."""

import argparse
from pathlib import Path
import stat
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parents[1]
MODULES = {
    "simple-tunnel": ("xzy.fz.SimpleProxyTunnel", ("simple-tunnel", "simple-tunnel.bat")),
    "nio-tunnel": ("xzy.fz.NIOProxyTunnel", ("startup.sh", "startup.bat", "startup.ps1", "StartupConfig.java")),
}


def package(module, main_class, companions, jar_dir, output_dir):
    jar = jar_dir / module / f"{module}.jar"
    # The versioned target/*.jar is a thin JAR, not the Shade output.
    with ZipFile(jar) as runnable:
        manifest = runnable.read("META-INF/MANIFEST.MF").decode("utf-8")
        if f"Main-Class: {main_class}" not in manifest.splitlines():
            raise ValueError(f"Not the expected runnable JAR: {jar}")

    files = {
        f"{module}.jar": jar,
        "config.properties": ROOT / module / "src/main/resources/config.properties",
        "README.md": ROOT / module / "README.md",
        "PROJECT_README.md": ROOT / "README.md",
        "LICENSE": ROOT / "LICENSE",
        **{name: ROOT / module / name for name in companions},
    }
    # Check all inputs before creating an archive.
    for source in files.values():
        if not source.is_file():
            raise FileNotFoundError(source)
    archive = output_dir / f"{module}.zip"
    with ZipFile(archive, "w", compression=ZIP_DEFLATED) as distribution:
        for name, source in files.items():
            executable = name in ("startup.sh", "simple-tunnel")
            data = source.read_bytes()
            if executable:
                data = data.replace(b"\r\n", b"\n")
            info = ZipInfo.from_file(source, arcname=f"{module}/{name}")
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | (0o755 if executable else 0o644)) << 16
            distribution.writestr(info, data, compress_type=ZIP_DEFLATED)
    print(archive)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar-dir", required=True, type=Path, help="base.export.path used by Maven")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for module, (main_class, companions) in MODULES.items():
        package(module, main_class, companions, args.jar_dir, args.output_dir)


if __name__ == "__main__":
    main()
