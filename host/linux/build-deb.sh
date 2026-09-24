#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DIR/../../" && pwd)"
BUILD_DIR="$DIR/build-deb"
PKG_NAME="watchmouse-host"
PKG_DIR="$BUILD_DIR/${PKG_NAME}"

# Version from app
VERSION="$(grep -m1 'versionName' "$REPO_ROOT/app/build.gradle" | sed -E 's/.*"([0-9.]+)".*/\1/')"
if [[ -z "$VERSION" ]]; then
  VERSION="$(date +%Y%m%d%H%M%S)"
fi
DEB_NAME="${PKG_NAME}_${VERSION}_all.deb"

rm -rf "$BUILD_DIR"
mkdir -p "$PKG_DIR/DEBIAN" "$PKG_DIR/opt/watchmouse-host" "$PKG_DIR/usr/lib/systemd/system" "$PKG_DIR/usr/share/icons/hicolor/256x256/apps" "$PKG_DIR/usr/share/applications"

# Control
sed "s/__VERSION__/$VERSION/" "$DIR/debian/control" > "$PKG_DIR/DEBIAN/control"

# Scripts
cp "$DIR/debian/postinst" "$PKG_DIR/DEBIAN/postinst"
cp "$DIR/debian/prerm" "$PKG_DIR/DEBIAN/prerm"
cp "$DIR/debian/postrm" "$PKG_DIR/DEBIAN/postrm"
chmod 755 "$PKG_DIR/DEBIAN/postinst" "$PKG_DIR/DEBIAN/prerm" "$PKG_DIR/DEBIAN/postrm"

# Files
cp "$DIR/watchmouse_host.py" "$PKG_DIR/opt/watchmouse-host/watchmouse_host.py"
chmod 644 "$PKG_DIR/opt/watchmouse-host/watchmouse_host.py"
cp "$DIR/watchmouse_host.service" "$PKG_DIR/usr/lib/systemd/system/watchmouse-host.service"
chmod 644 "$PKG_DIR/usr/lib/systemd/system/watchmouse-host.service"

# Icon (same as the watch app, 256x256 for the hicolor theme)
if [[ -f "$DIR/watchmouse-host.png" ]]; then
  cp "$DIR/watchmouse-host.png" "$PKG_DIR/usr/share/icons/hicolor/256x256/apps/watchmouse-host.png"
  chmod 644 "$PKG_DIR/usr/share/icons/hicolor/256x256/apps/watchmouse-host.png"
fi

# Desktop entry
cat > "$PKG_DIR/usr/share/applications/watchmouse-host.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=WatchMouse Host
Comment=Receive WatchMouse input from Wear OS over TCP (uinput)
Exec=true
Icon=watchmouse-host
Terminal=false
Categories=Utility;
StartupNotify=false
EOF
chmod 644 "$PKG_DIR/usr/share/applications/watchmouse-host.desktop"

# Build deb (root-owned payload, works even when run as a non-root user)
dpkg-deb --root-owner-group --build "$PKG_DIR" "$BUILD_DIR/$DEB_NAME"

echo "Built: $BUILD_DIR/$DEB_NAME"