#!/usr/bin/env bash
# Build the public libvpnhide_checks.so crate and wire it into the APK.
#
# Usage: ./scripts/build-app.sh [--release|--debug] [--install]
#   --release   Rust release profile (LTO/opt-level=z/strip) + APK assembleRelease (default)
#   --debug     Rust dev profile (unoptimized, faster) + APK assembleDebug
#   --install   Push resulting APK to connected device via adb install

set -euo pipefail

# 1. Determine script and repo directories
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Color constants for rich aesthetics in terminal output
BOLD="\033[1m"
GREEN="\033[1;32m"
BLUE="\033[1;34m"
YELLOW="\033[1;33m"
RED="\033[1;31m"
NC="\033[0m" # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# 2. Parse arguments
BUILD_TYPE="release"
INSTALL_APK=false

for arg in "$@"; do
    case "$arg" in
        --release) BUILD_TYPE="release" ;;
        --debug) BUILD_TYPE="debug" ;;
        --install) INSTALL_APK=true ;;
        *)
            log_error "Unknown argument: $arg"
            echo "Usage: $0 [--release|--debug] [--install]"
            exit 1
            ;;
    esac
done

log_info "Build type: ${BOLD}$BUILD_TYPE${NC}"
[[ "$INSTALL_APK" == "true" ]] && log_info "Will install APK on device after build"

# 3. Gobley's cargo plugin reads ANDROID_NDK_ROOT, not ANDROID_NDK_HOME
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
    else
        log_error "Neither ANDROID_NDK_ROOT nor ANDROID_NDK_HOME is set."
        exit 1
    fi
fi

# 4. Build the APK. Gobley compiles and packages libvpnhide_checks.so first.
APK_TASK="assembleRelease"
APK_OUT="app/build/outputs/apk/release/app-release.apk"
if [[ "$BUILD_TYPE" == "debug" ]]; then
    APK_TASK="assembleDebug"
    APK_OUT="app/build/outputs/apk/debug/app-debug.apk"
fi

log_info "Building APK (${BOLD}$APK_TASK${NC})..."
if ! (cd "$REPO_ROOT/lsposed" && ./gradlew "$APK_TASK"); then
    log_error "APK build failed!"
    exit 1
fi

APK_PATH="$REPO_ROOT/lsposed/$APK_OUT"
if [[ ! -f "$APK_PATH" ]]; then
    log_error "Expected APK not found at: $APK_PATH"
    exit 1
fi

log_success "APK ready: ${BOLD}$APK_PATH${NC}"

log_info "Verifying UniFFI exports packaged in the APK..."
python3 "$REPO_ROOT/scripts/verify-uniffi-exports.py" "$APK_PATH"

# 5. Install on device if requested
if [[ "$INSTALL_APK" == "true" ]]; then
    log_info "Checking ADB device connection..."
    if ! adb get-state >/dev/null 2>&1; then
        log_error "No ADB device connected or authorized. Please connect a device with USB debugging enabled."
        exit 1
    fi

    log_info "Installing APK on device..."
    if adb install -r "$APK_PATH"; then
        log_success "APK installed successfully"
    else
        log_error "APK installation failed"
        exit 1
    fi
fi
