#!/usr/bin/env bash
# NASQO DEV ONLY. Never point at production checkout or production DB.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
EXPECTED_BRANCH='feature/nasqo-arabic-invoice-pdf'
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
ARTIFACT_DIR="${NASQO_PDF_ARTIFACT_DIR:-$(mktemp -d)}"
mkdir -p "$ARTIFACT_DIR"
cd "$PACKAGE_DIR"
flutter --version
flutter pub get
flutter analyze lib/src/findoc/print/ lib/src/findoc/views/pdf_formats.dart test/findoc/print/
NASQO_PDF_ARTIFACT_DIR="$ARTIFACT_DIR" flutter test test/findoc/print/ --reporter expanded
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
echo "PDF artifact directory: $ARTIFACT_DIR"
echo 'Visual Arabic shaping and reference-image comparison require manual approval.'
