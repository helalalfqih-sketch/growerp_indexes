#!/usr/bin/env bash
# Intentional, explicit setup only — nothing touches NASQO production services.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$HOME/.pub-cache/bin:$PATH"

usage() {
  cat <<'EOF'
Usage: bash .devcontainer/bootstrap.sh MODE
  check     Verify development tools and Docker Engine
  flutter   Resolve packages and bootstrap the Flutter/Melos workspace
  backend   Initialize Moqui sources (may download large repositories)
  android   Install Android SDK packages (licenses must be accepted beforehand)

Before android mode: run sdkmanager --licenses interactively, review and
accept the Google Android SDK licenses only if you agree to their terms.
No database reset, demo data load, deploy, or production operation is performed.
EOF
}
case "${1:-}" in
  check)
    flutter --version
    java -version
    python3 --version
    node --version
    sdkmanager --version
    docker version
    docker compose version
    ;;
  flutter)
    cd "$repo_root/flutter"
    flutter pub get
    melos bootstrap
    ;;
  backend)
    cd "$repo_root"
    # GitHub Codespaces normally uses HTTPS; rewrite the public SSH submodule
    # URL for this command only, without changing any global Git config.
    # Keep this rewrite process-local so nested Git commands (including those
    # inside setup-backend.sh) use HTTPS, without changing global Git settings.
    export GIT_CONFIG_COUNT=1
    export GIT_CONFIG_KEY_0='url.https://github.com/.insteadOf'
    export GIT_CONFIG_VALUE_0='git@github.com:'
    git submodule update --init --recursive
    bash setup-backend.sh
    echo "Moqui sources ready. Run (cd moqui && ./gradlew --version) first."
    ;;
  android)
    echo "Android SDK licenses are a user decision; run sdkmanager --licenses first."
    sdkmanager --install 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
    flutter doctor -v
    ;;
  *)
    usage
    exit 2
    ;;
esac
