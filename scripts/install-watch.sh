#!/usr/bin/env bash
#
# Installe WatchMouse sur une montre Wear OS par ADB (première installation).
# Toutes les mises à jour suivantes se font ensuite via l'OTA intégré à l'app.
#
# Prérequis (une seule fois, sur la montre) :
#   Paramètres > Système > À propos > taper 7x sur "numéro de build"
#   Options développeur > Débogage ADB  (et "Débogage via Wi-Fi" si proposé)
#   Montre portée (ou sur chargeur) et sur le même Wi-Fi que ce PC
#
# Usage :
#   scripts/install-watch.sh                            # montre déjà connectée (seule)
#   scripts/install-watch.sh 192.168.1.42:5555          # adresse IP de la montre
#   scripts/install-watch.sh <serial>                   # si plusieurs appareils (ex: téléphone + montre)
#
# Si la montre demande un code (Android 11+) :
#   adb pair 192.168.1.42:39711   puis saisir le code affiché à l'écran

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$DIR/app/build/outputs/apk/release/app-universal-release.apk"
PKG="com.winnyking.watchmouse"

if [[ ! -f "$APK" ]]; then
  echo "ERREUR : APK introuvable. Construis-le d'abord avec :" >&2
  echo "  ./gradlew :app:assembleRelease" >&2
  exit 1
fi

if (( $# >= 1 )); then
  TARGET="$1"
  echo ". Connexion à $TARGET ..."
  if [[ "$TARGET" == *:* ]]; then
    adb connect "$TARGET" || true
  fi
else
  TARGET="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  if [[ -z "$TARGET" ]]; then
    echo "ERREUR : aucune montre connectée et autorisée." >&2
    echo "  - Options développeur > Débogage ADB activé sur la montre ?" >&2
    echo "  - Autorisation 'Allow USB debugging' acceptée à l'écran ?" >&2
    echo "  - Montre sur le poignet / chargeur (sinon le Wi-Fi se coupe) ?" >&2
    exit 1
  fi
  if [[ "$(wc -l <<< "$TARGET")" -gt 1 ]]; then
    echo "Plusieurs appareils détectés. Passe le serial de la montre en 1er argument :" >&2
    adb devices -l >&2
    exit 1
  fi
fi

if [[ "$TARGET" != *:* ]]; then
  SERIAL="$TARGET"
else
  SERIAL="$TARGET"
fi
SERIAL_ARGS=(-s "$SERIAL")
echo ". Cible : $SERIAL"

echo ". Attente de la montre ..."
timeout 30 adb "${SERIAL_ARGS[@]}" wait-for-device || {
  echo "ERREUR : montre cible injoignable." >&2
  exit 1
}

echo ". Installation de l'APK ..."
adb "${SERIAL_ARGS[@]}" install -r "$APK"

echo ". Lancement de WatchMouse ..."
adb "${SERIAL_ARGS[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

echo "OK : WatchMouse installé et lancé. Prochaines mises à jour : OTA dans l'app."