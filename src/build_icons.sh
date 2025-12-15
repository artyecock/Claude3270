#!/usr/bin/env bash
set -e

MASTER="icon.png"
APPNAME="Claude3270"

if [ ! -f "$MASTER" ]; then
  echo "ERROR: $MASTER not found"
  exit 1
fi

echo "Building icons from $MASTER"

###############################################################################
# Windows ICO
###############################################################################
# NOTE: ICO auto-resize does not allow per-size tuning.
# We rely on ImageMagick defaults here.
###############################################################################
echo "Generating Windows .ico..."
magick "$MASTER" \
  -define icon:auto-resize=256,128,64,48,32,24,16 \
  "${APPNAME}.ico"

###############################################################################
# macOS ICNS
###############################################################################
echo "Generating macOS .icns..."
ICONSET="${APPNAME}.iconset"
rm -rf "$ICONSET"
mkdir "$ICONSET"

# Small sizes: sharpen + boost green brightness
SMALL_OPTS="-sharpen 0x1 -modulate 115,100,100"

# Normal sizes: no enhancement
NORMAL_OPTS=""

# ---- Small ----
magick "$MASTER" -resize 16x16   $SMALL_OPTS "$ICONSET/icon_16x16.png"
magick "$MASTER" -resize 32x32   $SMALL_OPTS "$ICONSET/icon_16x16@2x.png"
magick "$MASTER" -resize 32x32   $SMALL_OPTS "$ICONSET/icon_32x32.png"
magick "$MASTER" -resize 64x64   $SMALL_OPTS "$ICONSET/icon_32x32@2x.png"

# ---- Medium ----
magick "$MASTER" -resize 128x128 $NORMAL_OPTS "$ICONSET/icon_128x128.png"
magick "$MASTER" -resize 256x256 $NORMAL_OPTS "$ICONSET/icon_128x128@2x.png"
magick "$MASTER" -resize 256x256 $NORMAL_OPTS "$ICONSET/icon_256x256.png"

# ---- Large ----
magick "$MASTER" -resize 512x512  "$ICONSET/icon_256x256@2x.png"
magick "$MASTER" -resize 512x512  "$ICONSET/icon_512x512.png"
magick "$MASTER" -resize 1024x1024 "$ICONSET/icon_512x512@2x.png"

iconutil -c icns "$ICONSET"

###############################################################################
# Linux PNGs
###############################################################################
echo "Generating Linux PNGs..."
LINUX_DIR="linux-icons"
rm -rf "$LINUX_DIR"
mkdir -p "$LINUX_DIR"

for size in 16 24 32; do
  magick "$MASTER" -resize ${size}x${size} \
    -sharpen 0x1 -modulate 115,100,100 \
    "$LINUX_DIR/${APPNAME}-${size}.png"
done

for size in 48 64 128 256 512; do
  magick "$MASTER" -resize ${size}x${size} \
    "$LINUX_DIR/${APPNAME}-${size}.png"
done

###############################################################################
echo "Done."
echo
echo "Outputs:"
echo "  ${APPNAME}.ico"
echo "  ${APPNAME}.icns"
echo "  linux-icons/*.png"

