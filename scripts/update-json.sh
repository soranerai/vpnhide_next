#!/usr/bin/env bash
# Generates Magisk/KSU update metadata for explicitly selected components.
# Run AFTER the relevant GitHub releases are published so their URLs are valid.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage: ./scripts/update-json.sh [options]

Without options, refreshes kmod and the built-in bridge/KPatch metadata for
the repository VERSION (legacy release behaviour).

Options:
  --kmod-version X.Y.Z    Refresh only update-kmod-*.json for this kmod release.
  --bridge-version X.Y.Z  Set the bridge artifact version in update-bridge.json.
  --kpatch-version X.Y.Z  Set the built-in kernel/KPatch version in update-bridge.json.
  -h, --help              Show this help.

--bridge-version and --kpatch-version may be supplied together when both
parts of the built-in update are released. Supplying just one preserves the
other component's existing metadata.
EOF
}

version_code() {
    local version="$1"
    if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "error: expected MAJOR.MINOR.PATCH, got '$version'" >&2
        exit 1
    fi
    local major minor patch
    IFS='.' read -r major minor patch <<< "$version"
    echo $((major * 10000 + minor * 100 + patch))
}

metadata_string() {
    local key="$1"
    sed -n "s/^[[:space:]]*\"${key}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*$/\1/p" \
        update-json/update-bridge.json | head -n 1
}

KMOD_VERSION=""
BRIDGE_VERSION=""
KPATCH_VERSION=""
EXPLICIT_COMPONENTS=false
while (($# > 0)); do
    case "$1" in
        --kmod-version)
            KMOD_VERSION="${2:-}"
            EXPLICIT_COMPONENTS=true
            shift 2
            ;;
        --bridge-version)
            BRIDGE_VERSION="${2:-}"
            EXPLICIT_COMPONENTS=true
            shift 2
            ;;
        --kpatch-version)
            KPATCH_VERSION="${2:-}"
            EXPLICIT_COMPONENTS=true
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown option '$1'" >&2
            usage >&2
            exit 2
            ;;
    esac
done

VERSION="$(tr -d '[:space:]' < VERSION)"
if ! $EXPLICIT_COMPONENTS; then
    KMOD_VERSION="$VERSION"
    BRIDGE_VERSION="$VERSION"
    KPATCH_VERSION="$VERSION"
fi

if [[ -n "$KMOD_VERSION" ]]; then version_code "$KMOD_VERSION" >/dev/null; fi
if [[ -n "$BRIDGE_VERSION" ]]; then version_code "$BRIDGE_VERSION" >/dev/null; fi
if [[ -n "$KPATCH_VERSION" ]]; then version_code "$KPATCH_VERSION" >/dev/null; fi

REPO="https://github.com/soranerai/vpnhide_next"
RAW="https://raw.githubusercontent.com/soranerai/vpnhide_next/main"

echo "Generating update-json: kmod=${KMOD_VERSION:-unchanged} bridge=${BRIDGE_VERSION:-unchanged} kpatch=${KPATCH_VERSION:-unchanged}"

mkdir -p update-json
ARTIFACT_DIR="$(mktemp -d)"
trap 'rm -rf "$ARTIFACT_DIR"' EXIT
KMOD_KMIS=("android12-5.10" "android13-5.10" "android13-5.15" "android14-5.15" "android14-6.1" "android15-6.6" "android16-6.12")
FAILED_ARTIFACTS=()
if [[ -n "$KMOD_VERSION" ]]; then
    KMOD_VERSION_CODE="$(version_code "$KMOD_VERSION")"
    for kmi in "${KMOD_KMIS[@]}"; do
        ARTIFACT="vpnhide-kmod-${kmi}.zip"
        ZIP_URL="${REPO}/releases/download/v${KMOD_VERSION}/${ARTIFACT}"
        if ! curl --fail --location --silent --show-error \
            "$ZIP_URL" -o "$ARTIFACT_DIR/$ARTIFACT"; then
            echo "  warning: artifact unavailable, keeping existing metadata: $ARTIFACT" >&2
            FAILED_ARTIFACTS+=("$ARTIFACT")
            continue
        fi
        SHA256="$(sha256sum "$ARTIFACT_DIR/$ARTIFACT" | cut -d' ' -f1)"
        cat > "update-json/update-kmod-${kmi}.json" <<EOJSON
{
  "version": "v${KMOD_VERSION}",
  "versionCode": ${KMOD_VERSION_CODE},
  "zipUrl": "${ZIP_URL}",
  "sha256": "${SHA256}",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
        echo "  update-json/update-kmod-${kmi}.json"
    done
fi

if [[ -n "$BRIDGE_VERSION" || -n "$KPATCH_VERSION" ]]; then
    if [[ -z "$BRIDGE_VERSION" ]]; then
        BRIDGE_VERSION="$(metadata_string version | sed 's/^v//')"
        BRIDGE_SHA256="$(metadata_string sha256)"
        BRIDGE_URL="$(metadata_string zipUrl)"
    else
        BRIDGE_ARTIFACT="vpnhide-bridge.zip"
        BRIDGE_URL="${REPO}/releases/download/v${BRIDGE_VERSION}/${BRIDGE_ARTIFACT}"
        if ! curl --fail --location --silent --show-error \
            "$BRIDGE_URL" -o "$ARTIFACT_DIR/$BRIDGE_ARTIFACT"; then
            echo "  warning: artifact unavailable, keeping existing metadata: $BRIDGE_ARTIFACT" >&2
            FAILED_ARTIFACTS+=("$BRIDGE_ARTIFACT")
            BRIDGE_VERSION=""
        else
            BRIDGE_SHA256="$(sha256sum "$ARTIFACT_DIR/$BRIDGE_ARTIFACT" | cut -d' ' -f1)"
        fi
    fi
    if [[ -z "$KPATCH_VERSION" ]]; then
        KPATCH_VERSION="$(metadata_string kernelVersion | sed 's/^v//')"
    fi
    if [[ -z "$BRIDGE_VERSION" || -z "$KPATCH_VERSION" || -z "$BRIDGE_SHA256" || -z "$BRIDGE_URL" ]]; then
        echo "error: incomplete bridge metadata; pass both --bridge-version and --kpatch-version" >&2
        exit 1
    fi
    BRIDGE_VERSION_CODE="$(version_code "$BRIDGE_VERSION")"
    KPATCH_VERSION_CODE="$(version_code "$KPATCH_VERSION")"

    cat > "update-json/update-bridge.json" <<EOJSON
{
  "version": "v${BRIDGE_VERSION}",
  "versionCode": ${BRIDGE_VERSION_CODE},
  "zipUrl": "${BRIDGE_URL}",
  "sha256": "${BRIDGE_SHA256}",
  "kernelVersion": "v${KPATCH_VERSION}",
  "kernelVersionCode": ${KPATCH_VERSION_CODE},
  "kernelReleasesApi": "https://api.github.com/repos/soranerai/GKI_KernelSU_SUSFS/releases?per_page=100",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
    echo "  update-json/update-bridge.json"
fi

if ((${#FAILED_ARTIFACTS[@]} > 0)); then
    echo "warning: ${#FAILED_ARTIFACTS[@]} artifact(s) were unavailable; metadata generation continued" >&2
fi
