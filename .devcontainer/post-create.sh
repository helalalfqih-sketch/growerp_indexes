#!/usr/bin/env bash
# Runs once after creating/rebuilding a NASQO Codespace; no production startup.
set -euo pipefail

export PATH="$HOME/.pub-cache/bin:$PATH"
echo "NASQO: checking the pinned toolchain..."
flutter config --no-analytics >/dev/null
dart pub global activate melos 7.8.1

flutter --version
java -version
python3 --version
node --version
docker --version
sdkmanager --version

echo
echo "Toolchain ready. Docker Engine should be available after the feature starts."
echo "Android platform packages and SDK licenses are NOT auto-installed or accepted."
echo "See docs/agents/nasqo-codespaces.md for safe bootstrap and build commands."
