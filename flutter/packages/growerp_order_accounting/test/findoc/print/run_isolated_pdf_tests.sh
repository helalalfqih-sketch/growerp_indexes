#!/usr/bin/env bash
# NASQO DEV ONLY. Never point at production checkout or production DB.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
EXPECTED_BRANCH='feature/nasqo-arabic-invoice-pdf'
ARTIFACT_DIR="${NASQO_PDF_ARTIFACT_DIR:-$(mktemp -d)}"
mkdir -p "$ARTIFACT_DIR"
STATUS_FILE="$ARTIFACT_DIR/stage-status.json"
MACHINE_LOG="$ARTIFACT_DIR/flutter-test-machine.jsonl"
CURRENT_STAGE=preflight
# Write a structured report on EVERY exit, including missing SDK and disk.
report_on_exit() {
  local original_status=$?
  trap - EXIT
  STAGE="$CURRENT_STAGE" EXIT_CODE="$original_status" STATUS_FILE="$STATUS_FILE" python3 - <<'PY_STATUS'
import json, os
from pathlib import Path
status = 'passed' if os.environ['EXIT_CODE'] == '0' else 'blocked'
Path(os.environ['STATUS_FILE']).write_text(json.dumps({
    'status': status, 'last_stage': os.environ['STAGE'],
    'exit_code': int(os.environ['EXIT_CODE'])}, indent=2) + '\n')
PY_STATUS
  python3 "$SCRIPT_DIR/invoice_ci_gate.py" --tests "$SCRIPT_DIR" \
    --machine-log "$MACHINE_LOG" --stage-status "$STATUS_FILE" \
    --report "$ARTIFACT_DIR/ci-gate-report.json" || true
  exit "$original_status"
}
trap report_on_exit EXIT
ACTUAL_BRANCH="$(git -C "$PACKAGE_DIR" branch --show-current)"
if [[ "$ACTUAL_BRANCH" != "$EXPECTED_BRANCH" ]]; then
  echo "Refusing to test branch: $ACTUAL_BRANCH" >&2
  exit 3
fi
if ! command -v flutter >/dev/null 2>&1; then
  echo 'BLOCKED: Flutter SDK is unavailable. Run in a separate CI runner with >= 10GB free.' >&2
  exit 2
fi
FREE_KB="$(df -Pk "$PACKAGE_DIR" | awk 'NR==2 {print $4}')"
if (( FREE_KB < 4000000 )); then
  echo 'BLOCKED: Less than 4GB free for Flutter tests. Use isolated CI.' >&2
  exit 2
fi
cd "$PACKAGE_DIR"
flutter --version
CURRENT_STAGE=dependencies
flutter pub get
CURRENT_STAGE=analysis
flutter analyze lib/src/findoc/print/ lib/src/findoc/views/pdf_formats.dart test/findoc/print/
CURRENT_STAGE=flutter-tests
# Machine output is preserved for a reproducible executed/passed/failed/skipped report.
set +e
NASQO_PDF_ARTIFACT_DIR="$ARTIFACT_DIR" flutter test test/findoc/print/ --machine > "$MACHINE_LOG" 2> "$ARTIFACT_DIR/flutter-test-stderr.log"
TEST_STATUS=$?
set -e
if (( TEST_STATUS != 0 )); then
  echo "Flutter tests failed with status $TEST_STATUS" >&2
  exit "$TEST_STATUS"
fi
CURRENT_STAGE=pdf-inspection
if [[ -f "$ARTIFACT_DIR/nasqo-45-items-a4.pdf" ]]; then
  if python3 -c 'import fitz' >/dev/null 2>&1; then
    python3 test/findoc/print/inspect_rendered_pdf.py \
      "$ARTIFACT_DIR/nasqo-45-items-a4.pdf" --min-pages 2 \
      --report "$ARTIFACT_DIR/audit.json"
  else
    echo 'BLOCKED: PyMuPDF unavailable; independent PDF audit not executed.' >&2
    exit 2
  fi
else
  echo 'BLOCKED: PDF artifact missing; visual audit not executed.' >&2
  exit 2
fi
CURRENT_STAGE=ci-gate
# Require zero source skips and zero runtime skips; do not treat green Flutter as release approval.
python3 - "$STATUS_FILE" <<'PY_STAGE'
import json, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({'status': 'passed', 'last_stage': 'ci-gate', 'exit_code': 0}))
PY_STAGE
python3 "$SCRIPT_DIR/invoice_ci_gate.py" --tests "$SCRIPT_DIR" \
  --machine-log "$MACHINE_LOG" --stage-status "$STATUS_FILE" \
  --report "$ARTIFACT_DIR/ci-gate-report.json"
echo "PDF artifact directory: $ARTIFACT_DIR"
echo 'Visual Arabic shaping and reference-image comparison require manual approval.'
