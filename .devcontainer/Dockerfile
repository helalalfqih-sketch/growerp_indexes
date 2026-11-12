# NASQO development only — production images live in deploy/nasqo/.
FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

USER root

ARG FLUTTER_VERSION=3.47.9
# From https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json
ARG FLUTTER_SHA256=25ed8c3c29d51a7353f2a944310b478af8214920639524b19c50a19e6c044c5a
# From https://developer.android.com/studio (Linux command line tools only).
ARG ANDROID_CMDLINE_REVISION=15859902
ARG ANDROID_CMDLINE_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583

ENV FLUTTER_ROOT=/opt/flutter \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="/opt/flutter/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates curl git unzip zip xz-utils python3 python3-venv \
    clang cmake ninja-build pkg-config libgtk-3-dev libclang-dev \
    libpulse-dev libglu1-mesa \
    && rm -rf /var/lib/apt/lists/*

# Flutter is intentionally pinned to the release used by NASQO's Android CI.
RUN curl --fail --location --retry 3 --silent --show-error \
      "https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_${FLUTTER_VERSION}-stable.tar.xz" \
      -o /tmp/flutter.tar.xz \
    && echo "${FLUTTER_SHA256}  /tmp/flutter.tar.xz" | sha256sum -c - \
    && tar -xJf /tmp/flutter.tar.xz -C /opt \
    && rm /tmp/flutter.tar.xz \
    && git config --system --add safe.directory /opt/flutter \
    && chown -R vscode:vscode /opt/flutter

# Only the SDK command-line tools are installed here. SDK platform packages
# and their licenses are handled explicitly by the Codespace user.
RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && curl --fail --location --retry 3 --silent --show-error \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_REVISION}_latest.zip" \
      -o /tmp/android-commandline.zip \
    && echo "${ANDROID_CMDLINE_SHA256}  /tmp/android-commandline.zip" | sha256sum -c - \
    && unzip -q /tmp/android-commandline.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/android-commandline.zip \
    && chown -R vscode:vscode /opt/android-sdk

USER vscode
