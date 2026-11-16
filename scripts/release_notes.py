#!/usr/bin/env python3
"""Validate a release tag and extract its Markdown notes (v0.3.0 and later)."""

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
MINIMUM_VERSION = (0, 3, 0)
TAG = re.compile(
    r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
)


def uses_versioned_notes(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError(f"Invalid release tag: {tag!r}; expected vMAJOR.MINOR.PATCH")
    return tuple(int(part) for part in match.groups()) >= MINIMUM_VERSION


def extract_notes(markdown, tag):
    """Match a unique level-two version heading, ignoring fenced code blocks."""
    lines = markdown.splitlines(keepends=True)
    headings = []
    fence = None
    for index, line in enumerate(lines):
        marker = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line.rstrip("\r\n"))
        if fence:
            if marker and marker[1][0] == fence[0] and len(marker[1]) >= len(fence) and not marker[2].strip():
                fence = None
            continue
        if marker:
            fence = marker[1]
            continue
        heading = re.match(r"^ {0,3}##[ \t]+(.+?)[ \t]*$", line.rstrip("\r\n"))
        if heading:
            title = re.sub(r"[ \t]+#+[ \t]*$", "", heading[1]).strip()
            headings.append((index, title))
    matches = [position for position, (_, title) in enumerate(headings) if title == tag]
    if len(matches) != 1:
        raise ValueError(f"Expected one '## {tag}' section, found {len(matches)}")
    position = matches[0]
    start = headings[position][0] + 1
    end = headings[position + 1][0] if position + 1 < len(headings) else len(lines)
    body = "".join(lines[start:end]).strip()
    meaningful = re.sub(r"<!--.*?-->", "", body, flags=re.DOTALL)
    if not any(line.strip() and not re.match(r"^\s*#{1,6}\s", line) for line in meaningful.splitlines()):
        raise ValueError(f"Release notes for {tag} are empty")
    return body + "\n"


def prepare(tag, root=ROOT):
    # Legacy releases still publish, but do not synchronize handwritten notes.
    if not uses_versioned_notes(tag):
        return None
    for module in ("simple-tunnel", "nio-tunnel"):
        pom = ET.parse(root / module / "pom.xml").getroot()
        version = pom.findtext("{http://maven.apache.org/POM/4.0.0}version")
        if version != tag[1:]:
            raise ValueError(f"{module}/pom.xml version {version!r} does not match {tag}")
    return extract_notes((root / "RELEASE_NOTES.md").read_text(encoding="utf-8-sig"), tag)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)
    try:
        notes = prepare(args.tag)
    except (ValueError, OSError, ET.ParseError) as error:
        print(f"Release validation failed: {error}", file=sys.stderr)
        return 1
    if notes is not None:
        with args.output.open("w", encoding="utf-8", newline="\n") as output:
            output.write(notes)
        print(f"Prepared release notes for {args.tag}: {args.output}")
    else:
        print(f"Publishing {args.tag} without handwritten notes; synchronization begins at v0.3.0")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8", newline="\n") as output:
            output.write(f"notes_enabled={'true' if notes is not None else 'false'}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
