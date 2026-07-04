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
KMOD_KMIS=("android12-5.10" "android13-5.10" "android13-5.15" "android14-5.15" "android14-6.1" "android15-6.6" "android16-6.12")
for kmi in "${KMOD_KMIS[@]}"; do
    cat > "update-json/update-kmod-${kmi}.json" <<EOJSON
{
  "version": "v${VERSION}",
  "versionCode": ${VERSION_CODE},
  "zipUrl": "${REPO}/releases/download/v${VERSION}/vpnhide-kmod-${kmi}.zip",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
    echo "  update-json/update-kmod-${kmi}.json"
done


