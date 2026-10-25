#!/bin/bash
# AutoPat 一键构建：$1 = 版本号
set -u
V="${1:-test}"
SRC=/sdcard/AutoPat_MT
OUT=/sdcard/Download/AutoPat_${V}.apk
W=/tmp/bld_${V}

echo "===== 1/6 清理工作区 ====="
rm -rf "$W"
mkdir -p "$W/classes"

echo "===== 2/6 javac ====="
javac -source 8 -target 8 -encoding UTF-8 \
  -cp /usr/lib/android-sdk/platforms/android-23/android.jar:$SRC/libs/api-82.jar \
  -d "$W/classes" $(find $SRC/src -name '*.java') 2>&1 | grep -v 'warning' | head -20

if [ ! -d "$W/classes/com" ]; then
  echo "!! javac 失败"
  exit 1
fi

echo "===== 3/6 d8 ====="
java -cp /tmp/r8lib.jar com.android.tools.r8.D8 --min-api 24 \
  --output "$W" $(find "$W/classes" -name '*.class') 2>&1 | head -5

if [ ! -f "$W/classes.dex" ]; then
  echo "!! d8 失败"
  exit 1
fi
echo "dex 大小: $(stat -c %s "$W/classes.dex")"

echo "===== 4/6 aapt ====="
cd "$W" || exit 1
aapt package -f -M "$SRC/AndroidManifest.xml" -A "$SRC/assets" \
  -I /usr/lib/android-sdk/platforms/android-23/android.jar -F "$W/base.apk" 2>&1 | head -5

echo "===== 5/6 打包 dex ====="
rm -f "$OUT"
python3 - "$W/base.apk" "$W/classes.dex" "$OUT" <<'PYEOF'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
src = zipfile.ZipFile(base, 'r')
dst = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for it in src.infolist():
    if it.filename == 'classes.dex':
        continue
    dst.writestr(it, src.read(it.filename))
src.close()
dst.write(dex, 'classes.dex')
dst.close()
z = zipfile.ZipFile(out)
print('APK 内容:', z.namelist())
print('dex 大小:', z.getinfo('classes.dex').file_size)
PYEOF

echo "===== 6/6 对齐+签名 ====="
zipalign -f 4 "$OUT" "$W/aligned.apk" && mv "$W/aligned.apk" "$OUT"
apksigner sign --ks /tmp/build/key.jks --ks-key-alias autopat \
  --ks-pass pass:autopat123 --key-pass pass:autopat123 "$OUT"
echo "最终: $(ls -la $OUT)"