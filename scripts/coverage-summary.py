#!/usr/bin/env python3
"""Print Kover line coverage (overall and raftlab-core) as a Markdown table.

Usage: python3 -I scripts/coverage-summary.py <kover-report.xml>
Reads the JaCoCo-format XML written by `./gradlew :koverXmlReport`.
Exit codes: 0 ok; 2 usage error, unreadable file, or missing LINE counters.
"""

import sys
import xml.etree.ElementTree as ET

CORE_PACKAGE = "io/raftlab/core"


def line_counter(element):
    for counter in element.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered")), int(counter.get("missed"))
    return None


def row(scope, covered, missed):
    total = covered + missed
    pct = "n/a" if total == 0 else f"{covered * 100 / total:.2f}%"
    return f"| {scope} | {covered} | {total} | {pct} |"


def main(argv):
    if len(argv) != 2:
        print("usage: coverage-summary.py <kover-report.xml>", file=sys.stderr)
        return 2
    try:
        root = ET.parse(argv[1]).getroot()
    except (OSError, ET.ParseError) as e:
        print(f"coverage-summary: cannot read {argv[1]}: {e}", file=sys.stderr)
        return 2
    overall = line_counter(root)
    core_covered = 0
    core_missed = 0
    found_core = False
    for package in root.findall("package"):
        name = package.get("name", "")
        if name == CORE_PACKAGE or name.startswith(CORE_PACKAGE + "/"):
            counts = line_counter(package)
            if counts is not None:
                found_core = True
                core_covered += counts[0]
                core_missed += counts[1]
    if overall is None or not found_core:
        print("coverage-summary: LINE counters for the report or io/raftlab/core not found", file=sys.stderr)
        return 2
    print("## Coverage (Kover, line)")
    print()
    print("| Scope | Covered lines | Total lines | Line coverage |")
    print("|-------|---------------|-------------|---------------|")
    print(row("Overall (all modules)", *overall))
    print(row("raftlab-core (gate: >= 85%)", core_covered, core_missed))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
