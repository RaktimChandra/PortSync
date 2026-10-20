#!/usr/bin/env python3
"""Summarise JUnit XML reports for a CI job: totals per module as one notice, and each failing
test (up to eight) as an error annotation, so a red run names its tests through the checks API.

usage: test_summary.py <label> [glob ...]
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    label = sys.argv[1]
    patterns = sys.argv[2:] or ["**/target/surefire-reports/TEST-*.xml", "**/target/failsafe-reports/TEST-*.xml"]
    rows, failures = {}, []
    for pattern in patterns:
        for path in glob.glob(pattern, recursive=True):
            module = path.replace("\\", "/").split("/target/")[0] or "."
            r = rows.setdefault(module, {"tests": 0, "failed": 0, "skipped": 0})
            for case in ET.parse(path).getroot().iter("testcase"):
                r["tests"] += 1
                bad = case.find("failure") if case.find("failure") is not None else case.find("error")
                if bad is not None:
                    r["failed"] += 1
                    msg = " ".join((bad.get("message") or bad.get("type") or "").split())[:500]
                    failures.append(f"{case.get('classname')} > {case.get('name')}: {msg}")
                elif case.find("skipped") is not None:
                    r["skipped"] += 1
    total = {k: sum(r[k] for r in rows.values()) for k in ("tests", "failed", "skipped")}
    lines = [f"### {label}", "", "| module | tests | failed | skipped |", "|---|---:|---:|---:|"]
    lines += [f"| {m} | {r['tests']} | {r['failed']} | {r['skipped']} |" for m, r in sorted(rows.items())]
    lines.append(f"| **total** | **{total['tests']}** | **{total['failed']}** | **{total['skipped']}** |")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    compact = "; ".join(f"{m}={r['tests']}/{r['failed']}/{r['skipped']}" for m, r in sorted(rows.items()))
    print(f"::notice title={label} (tests/failed/skipped)::total={total['tests']}/{total['failed']}/{total['skipped']} | {compact}")
    for f in failures[:8]:
        print(f"::error title={label} failed test::{f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
