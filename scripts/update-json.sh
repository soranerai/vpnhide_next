#!/usr/bin/env bash
# Generates Magisk/KSU updateJson files pointing to the current VERSION.
# Run AFTER the GitHub release is published so zipUrl is already valid.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(tr -d '[:space:]' < VERSION)"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: VERSION must be MAJOR.MINOR.PATCH, got '$VERSION'" >&2
    exit 1
fi

IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION"
VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))

REPO="https://github.com/soranerai/vpnhide_next"
RAW="https://raw.githubusercontent.com/soranerai/vpnhide_next/main"

echo "Generating update-json for v${VERSION} (versionCode: $VERSION_CODE)"

mkdir -p update-json
ARTIFACT_DIR="$(mktemp -d)"
trap 'rm -rf "$ARTIFACT_DIR"' EXIT
KMOD_KMIS=("android12-5.10" "android13-5.10" "android13-5.15" "android14-5.15" "android14-6.1" "android15-6.6" "android16-6.12")
for kmi in "${KMOD_KMIS[@]}"; do
    ARTIFACT="vpnhide-kmod-${kmi}.zip"
    ZIP_URL="${REPO}/releases/download/v${VERSION}/${ARTIFACT}"
    curl --fail --location --silent --show-error \
        "$ZIP_URL" -o "$ARTIFACT_DIR/$ARTIFACT"
    SHA256="$(sha256sum "$ARTIFACT_DIR/$ARTIFACT" | cut -d' ' -f1)"
    cat > "update-json/update-kmod-${kmi}.json" <<EOJSON
{
  "version": "v${VERSION}",
  "versionCode": ${VERSION_CODE},
  "zipUrl": "${ZIP_URL}",
  "sha256": "${SHA256}",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
    echo "  update-json/update-kmod-${kmi}.json"
done

BRIDGE_ARTIFACT="vpnhide-bridge.zip"
BRIDGE_URL="${REPO}/releases/download/v${VERSION}/${BRIDGE_ARTIFACT}"
curl --fail --location --silent --show-error \
    "$BRIDGE_URL" -o "$ARTIFACT_DIR/$BRIDGE_ARTIFACT"
BRIDGE_SHA256="$(sha256sum "$ARTIFACT_DIR/$BRIDGE_ARTIFACT" | cut -d' ' -f1)"

cat > "update-json/update-bridge.json" <<EOJSON
{
  "version": "v${VERSION}",
  "versionCode": ${VERSION_CODE},
  "zipUrl": "${BRIDGE_URL}",
  "sha256": "${BRIDGE_SHA256}",
  "kernelVersion": "v${VERSION}",
  "kernelVersionCode": ${VERSION_CODE},
  "kernelReleasesApi": "https://api.github.com/repos/soranerai/GKI_KernelSU_SUSFS/releases?per_page=100",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
echo "  update-json/update-bridge.json"

