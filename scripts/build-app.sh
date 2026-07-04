#!/usr/bin/env bash
# Build libvpnhide_checks.so in the private repo (vpnhide_next_private,
# symlinked in at the repo root) and wire it into this repo's lsposed APK.
#
# Usage: ./scripts/build-app.sh [--release|--debug] [--install]
#   --release   Rust release profile (LTO/opt-level=z/strip) + APK assembleRelease (default)
#   --debug     Rust dev profile (unoptimized, faster) + APK assembleDebug
#   --install   Push resulting APK to connected device via adb install

set -euo pipefail

# 1. Determine script and repo directories
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PRIVATE_REPO="$REPO_ROOT/vpnhide_next_private"

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

# 3. Verify the private repo is checked out where we expect it
if [[ ! -d "$PRIVATE_REPO/lsposed/native" ]]; then
    log_error "Private repo not found at $PRIVATE_REPO/lsposed/native"
    log_error "(expected a checkout of vpnhide_next_private, symlinked in at the repo root)"
    exit 1
fi

# 4. Gobley's cargo plugin reads ANDROID_NDK_ROOT, not ANDROID_NDK_HOME
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
    else
        log_error "Neither ANDROID_NDK_ROOT nor ANDROID_NDK_HOME is set."
        exit 1
    fi
fi

# 5. Build libvpnhide_checks.so via Gradle + Gobley, in the private repo
CARGO_TASK="cargoBuildAndroidArm64Release"
[[ "$BUILD_TYPE" == "debug" ]] && CARGO_TASK="cargoBuildAndroidArm64Debug"

log_info "Building libvpnhide_checks.so (${BOLD}$CARGO_TASK${NC}) in private repo..."
if ! (cd "$PRIVATE_REPO/lsposed" && ./gradlew ":app:$CARGO_TASK"); then
    log_error "Native library build failed!"
    exit 1
fi
log_success "Native library build completed."

# 6. Locate the built .so and copy it into the public repo's jniLibs
SO_SRC="$PRIVATE_REPO/lsposed/native/target/aarch64-linux-android/$BUILD_TYPE/libvpnhide_checks.so"
if [[ ! -f "$SO_SRC" ]]; then
    log_error "Expected .so not found at: $SO_SRC"
    exit 1
fi

JNI_DIR="$REPO_ROOT/lsposed/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
cp "$SO_SRC" "$JNI_DIR/libvpnhide_checks.so"
log_success "Copied $(du -h "$SO_SRC" | cut -f1) .so into ${BOLD}$JNI_DIR${NC}"

# 7. Build the APK in the public repo
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

# 8. Install on device if requested
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
