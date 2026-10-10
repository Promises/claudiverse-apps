#!/bin/sh
# Render macos/Icon/AppIcon.svg into the app's icon set (every size macOS asks
# for). Run after changing the SVG; the PNGs are kept in git, so a build does
# not need this.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
set_dir="$here/Claudiverse/Assets.xcassets/AppIcon.appiconset"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# QuickLook renders SVG with WebKit, masks and filters included.
qlmanage -t -s 1024 -o "$work" "$here/Icon/AppIcon.svg" >/dev/null 2>&1
master="$work/AppIcon.svg.png"
[ -f "$master" ] || { echo "render failed" >&2; exit 1; }

mkdir -p "$set_dir"
for size in 16 32 128 256 512; do
  sips -z $size $size "$master" --out "$set_dir/icon_${size}.png" >/dev/null
  double=$((size * 2))
  sips -z $double $double "$master" --out "$set_dir/icon_${size}_2x.png" >/dev/null
done

cat > "$set_dir/Contents.json" <<JSON
{
  "images" : [
    { "idiom" : "mac", "scale" : "1x", "size" : "16x16", "filename" : "icon_16.png" },
    { "idiom" : "mac", "scale" : "2x", "size" : "16x16", "filename" : "icon_16_2x.png" },
    { "idiom" : "mac", "scale" : "1x", "size" : "32x32", "filename" : "icon_32.png" },
    { "idiom" : "mac", "scale" : "2x", "size" : "32x32", "filename" : "icon_32_2x.png" },
    { "idiom" : "mac", "scale" : "1x", "size" : "128x128", "filename" : "icon_128.png" },
    { "idiom" : "mac", "scale" : "2x", "size" : "128x128", "filename" : "icon_128_2x.png" },
    { "idiom" : "mac", "scale" : "1x", "size" : "256x256", "filename" : "icon_256.png" },
    { "idiom" : "mac", "scale" : "2x", "size" : "256x256", "filename" : "icon_256_2x.png" },
    { "idiom" : "mac", "scale" : "1x", "size" : "512x512", "filename" : "icon_512.png" },
    { "idiom" : "mac", "scale" : "2x", "size" : "512x512", "filename" : "icon_512_2x.png" }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
JSON
printf '{\n  "info" : { "author" : "xcode", "version" : 1 }\n}\n' > "$here/Claudiverse/Assets.xcassets/Contents.json"
echo "icon set: $set_dir"
