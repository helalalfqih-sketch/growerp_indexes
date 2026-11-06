#!/usr/bin/env bash
# Build the GrowERP Admin Flutter SPA for a static web host (Vercel).
# Nothing in this script deploys a backend or mutates accounting data.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLUTTER_VERSION="3.47.9"
FLUTTER_DIR="${TMPDIR:-/tmp}/growerp-flutter-${FLUTTER_VERSION}"

if ! command -v flutter >/dev/null 2>&1; then
  if [[ ! -x "$FLUTTER_DIR/bin/flutter" ]]; then
    echo "Installing pinned Flutter ${FLUTTER_VERSION} SDK"
    git clone --quiet --depth 1 --branch "$FLUTTER_VERSION" \
      https://github.com/flutter/flutter.git "$FLUTTER_DIR"
  fi
  export PATH="$FLUTTER_DIR/bin:$PATH"
fi

flutter --version
flutter config --enable-web
dart pub global activate melos 8.10.1
export PATH="$HOME/.pub-cache/bin:$PATH"

cd "$REPO_ROOT/flutter"
melos bootstrap
melos l10n --no-select
melos build --no-select

cd packages/admin
# The web UI uses the existing configured GrowERP API server. It must be
# replaced with an authorized, CORS-enabled Moqui backend before production.
flutter build web --release --pwa-strategy=none --base-href=/

test -s build/web/index.html
test -s build/web/flutter_bootstrap.js
echo "Admin web build produced at $PWD/build/web"
