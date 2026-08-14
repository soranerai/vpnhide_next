# CI image for vpnhide_next.
#
# Bakes in everything needed for the public build:
#   - JDK 17 (lsposed)
#   - Google AOSP clang + cross-compile tools (kmod)
#
# Rebuilt by ci-image.yml when this Dockerfile changes (and monthly).

FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates curl unzip zip xz-utils \
        build-essential pkg-config \
        cmake ninja-build \
        openjdk-17-jdk-headless \
        python3 \
        shellcheck \
        git && \
    rm -rf /var/lib/apt/lists/*

# ── ktlint (for lsposed Kotlin) ─────────────────────────────────────
ENV KTLINT_VERSION=1.8.0
RUN curl -fsSL -o /usr/local/bin/ktlint \
        "https://github.com/pinterest/ktlint/releases/download/${KTLINT_VERSION}/ktlint" && \
    chmod +x /usr/local/bin/ktlint

# ── Android SDK (for lsposed) ────────────────────────────────────────
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" && \
    curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools && \
    mv /tmp/cmdline-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" && \
    rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools && \
    yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" && \
    chmod -R a+rx "${ANDROID_HOME}"
