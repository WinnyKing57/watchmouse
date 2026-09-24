#!/usr/bin/env bash
# Configure GitHub Actions secrets so CI can build production-signed release
# APKs with the same keystore used locally (required for OTA).
#
# Reads keystore.properties + the local keystore/watchmouse.jks and uploads them
# as GitHub secrets. Secrets are never printed.
#
# Usage:
#   scripts/ci-secrets.sh            # configure the secrets
#   scripts/ci-secrets.sh --dry-run  # only show which secrets would be set

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(git -C "$DIR" remote get-url origin | sed -E 's#.*github.com[:/]##; s#\.git$##')"
KS_PROPS="$DIR/keystore.properties"
KS_FILE="$(grep -m1 '^storeFile=' "$KS_PROPS" | cut -d= -f2-)"

if [[ ! -f "$KS_PROPS" ]]; then
  echo "ERREUR : keystore.properties introuvable ($KS_PROPS)." >&2
  exit 1
fi
if [[ ! -f "$KS_FILE" ]]; then
  echo "ERREUR : keystore introuvable ($KS_FILE)." >&2
  exit 1
fi

STORE_PASSWORD="$(grep -m1 '^storePassword=' "$KS_PROPS" | cut -d= -f2-)"
KEY_ALIAS="$(grep -m1 '^keyAlias=' "$KS_PROPS" | cut -d= -f2-)"
KEY_PASSWORD="$(grep -m1 '^keyPassword=' "$KS_PROPS" | cut -d= -f2-)"

if [[ -z "$STORE_PASSWORD" || -z "$KEY_ALIAS" || -z "$KEY_PASSWORD" ]]; then
  echo "ERREUR : champs storePassword/keyAlias/keyPassword manquants." >&2
  exit 1
fi

TMP="$(mktemp)" ; trap 'rm -f "$TMP"' EXIT
base64 -w0 "$KS_FILE" > "$TMP"

set_secret() {
  local name="$1" value="$2"
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    echo "[dry-run] gh secret set $name (repo: $REPO)"
  else
    gh secret set "$name" --repo "$REPO" --body "$value"
    echo "OK: $name"
  fi
}

set_secret "KEYSTORE_BASE64" "$(cat "$TMP")" || true
set_secret "KEYSTORE_STORE_PASSWORD" "$STORE_PASSWORD"
set_secret "KEY_ALIAS" "$KEY_ALIAS"
set_secret "KEYSTORE_KEY_PASSWORD" "$KEY_PASSWORD"

echo "Secrets GitHub configurés sur $REPO (4/4 si aucune erreur ci-dessus)."