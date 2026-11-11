#!/usr/bin/env python3
"""Fail-closed NASQO invoice test gate and inspectable CI report.

No network, production access, or external Python packages. The JSON machine
test log is optional: missing logs are recorded as NOT_RUN, never as PASS.
"""
import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

REQUIRED = tuple(f"INV-{i:02d}" for i in range(1, 13))
SKIP_PATTERN = re.compile(r"\bskip\s*:\s*(['\"])(.*?)\1", re.DOTALL)
INV_PATTERN = re.compile(r"\bINV-(?:0[1-9]|1[0-2])\b")


def collect(root: Path, machine_log: Path | None):
    entries = []
    for file in sorted(root.glob("*_test.dart")):
        source = file.read_text(encoding="utf-8")
        for match in SKIP_PATTERN.finditer(source):
            before = source[:match.start()]
            test_names = list(re.finditer(r"\btest(?:Widgets)?\s*\(\s*['\"]([^'\"]+)", before))
            name = test_names[-1].group(1) if test_names else "unknown"
            entries.append({"file": file.name, "test": name,
                            "reason": match.group(2),
                            "ids": sorted(set(INV_PATTERN.findall(name)))})
    counts = {"passed": 0, "failed": 0, "skipped": 0, "started": 0}
    errors = []
    executed_inv = set()
    test_names = {}
    if machine_log and machine_log.is_file():
        for number, line in enumerate(machine_log.read_text(encoding="utf-8").splitlines(), 1):
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                # Flutter may print non-JSON tool output; keep explicit evidence.
                continue
            if not isinstance(event, dict):
                errors.append({"line": number,
                               "error": "Non-object JSON in machine log"})
                continue
            kind = event.get("type")
            if kind == "testStart":
                counts["started"] += 1
                test = event.get("test") or {}
                test_names[test.get("id")] = test.get("name", "")
            elif kind == "testDone":
                if not event.get("skipped") and event.get("result") == "success":
                    executed_inv.update(INV_PATTERN.findall(
                        test_names.get(event.get("testID"), "")))
                if event.get("skipped"):
                    counts["skipped"] += 1
                elif event.get("result") == "success":
                    counts["passed"] += 1
                else:
                    counts["failed"] += 1
            elif kind == "error":
                errors.append({"testID": event.get("testID"),
                               "error": str(event.get("error", ""))[:500]})
    return entries, counts, errors, executed_inv


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tests", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--machine-log", type=Path)
    parser.add_argument("--stage-status", type=Path)
    args = parser.parse_args()
    pending, counts, errors, executed_inv = collect(args.tests, args.machine_log)
    stage = {}
    if args.stage_status and args.stage_status.is_file():
        stage = json.loads(args.stage_status.read_text(encoding="utf-8"))
    covered = set()
    for file in args.tests.glob("*_test.dart"):
        covered.update(INV_PATTERN.findall(file.read_text(encoding="utf-8")))
    missing = sorted(set(REQUIRED) - covered)
    log_exists = bool(args.machine_log and args.machine_log.is_file())
    # Explicitly separate test execution from source-level coverage.
    tests_executed = log_exists and counts["started"] > 0
    blockers = []
    if pending:
        blockers.append(f"{len(pending)} source-level skipped test(s)")
    if missing:
        blockers.append("Missing INV IDs: " + ", ".join(missing))
    if not tests_executed:
        blockers.append("Flutter machine test log missing or no tests started")
    not_executed = sorted(set(REQUIRED) - executed_inv)
    if not_executed:
        blockers.append("INV IDs without passing runtime evidence: " +
                        ", ".join(not_executed))
    if counts["failed"] or counts["skipped"] or errors:
        blockers.append("Flutter tests failed, skipped, or emitted errors")
    if stage.get("status") != "passed":
        blockers.append("Full runner stages did not finish successfully")
    result = {
        "schema": "nasqo.invoice.ci-gate.v1",
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "release_ready": False,  # Manual visual and backend gates remain.
        "ci_gate_passed": not blockers,
        "stage": stage,
        "machine_log_present": log_exists,
        "flutter_tests_executed": tests_executed,
        "flutter_test_counts": counts,
        "flutter_errors": errors,
        "required_inv_ids": list(REQUIRED),
        "missing_inv_ids_in_source": missing,
        "passed_inv_ids_from_machine": sorted(executed_inv),
        "inv_ids_without_passing_evidence": not_executed,
        "blocked_skipped_tests": pending,
        "blockers": blockers,
        "note": "Green CI is not production approval; visual, backend and restore gates remain.",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    print(json.dumps({"ci_gate_passed": result["ci_gate_passed"],
                      "counts": counts, "blockers": blockers}, ensure_ascii=False))
    return 0 if result["ci_gate_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
