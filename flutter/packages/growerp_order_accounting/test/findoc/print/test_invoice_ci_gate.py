#!/usr/bin/env python3
"""Unit tests for the offline, fail-closed CI evidence collector."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("invoice_ci_gate.py")


class GateTests(unittest.TestCase):
    def run_gate(self, skipped=False, machine=True, stage="passed", prefix=""):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tests = root / "tests"
            tests.mkdir()
            for i in range(1, 13):
                marker = ", skip: 'pending'" if skipped and i == 5 else ""
                (tests / f"inv_{i:02d}_test.dart").write_text(
                    f"test('INV-{i:02d} contract', () {{}}{marker});\n")
            status = root / "stage.json"
            status.write_text(json.dumps({"status": stage}))
            log = root / "machine.jsonl"
            if machine:
                log.write_text(prefix + ''.join(
                    json.dumps({"type": "testStart", "test": {"id": i,
                        "name": f"INV-{i:02d} contract"}}) + "\n" +
                    json.dumps({"type": "testDone", "testID": i,
                        "result": "success", "skipped": False}) + "\n"
                    for i in range(1, 13)))
            report = root / "report.json"
            command = [sys.executable, str(SCRIPT), "--tests", str(tests),
                       "--stage-status", str(status), "--report", str(report),
                       "--machine-log", str(log)]
            process = subprocess.run(command, capture_output=True, text=True)
            return process.returncode, json.loads(report.read_text())

    def test_flutter_process_metadata_does_not_count_as_test_evidence(self):
        prefix = json.dumps([{"event": "test.startedProcess", "params": {"vmServiceUri": None}}]) + "\n"
        code, report = self.run_gate(prefix=prefix)
        self.assertEqual(code, 0)
        self.assertEqual(report["flutter_test_counts"]["passed"], 12)

    def test_non_object_json_reports_failure_without_crashing(self):
        for value in ([], [1], None, "noise", 42):
            with self.subTest(value=value):
                code, report = self.run_gate(prefix=json.dumps(value) + "\n")
                self.assertEqual(code, 1)
                self.assertFalse(report["ci_gate_passed"])
                self.assertEqual(report["flutter_test_counts"]["passed"], 12)
                self.assertTrue(report["flutter_errors"])

    def test_source_skip_fails_even_if_machine_passes(self):
        code, report = self.run_gate(skipped=True)
        self.assertEqual(code, 1)
        self.assertFalse(report["ci_gate_passed"])
        self.assertEqual(report["blocked_skipped_tests"][0]["ids"], ["INV-05"])

    def test_missing_machine_log_fails(self):
        code, report = self.run_gate(machine=False)
        self.assertEqual(code, 1)
        self.assertFalse(report["flutter_tests_executed"])

    def test_failed_stage_fails(self):
        code, report = self.run_gate(stage="blocked")
        self.assertEqual(code, 1)
        self.assertFalse(report["ci_gate_passed"])

    def test_clean_synthetic_evidence_passes_ci_gate_only(self):
        code, report = self.run_gate()
        self.assertEqual(code, 0)
        self.assertTrue(report["ci_gate_passed"])
        self.assertFalse(report["release_ready"])


if __name__ == "__main__":
    unittest.main()
