#!/usr/bin/env bash
# ============================================================
# 荔枝直播·一体化版 —— aapt2 + javac + d8 + apksigner（无 Gradle）
#
# 依赖：libs/geckoview-69.aar（GeckoView 69.0.1 的通用 AAR，约 150MB，仓库里不放）
#       自己准备一份放到 libs/ 即可；也可以用 GV_AAR=/path/to/xxx.aar 指定。
# 产物：out/LizhiGecko-1.3.1.apk   包名 com.newbox.lizhigecko
# 用法：bash build.sh          （VER=1.2 VERCODE=3 可覆盖版本号）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-C:/Users/Jian/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JDK="${JDK_HOME:-D:/Program Files/Android/Android Studio/jbr}"
JAVA="$JDK/bin/java.exe"
JAVAC="$JDK/bin/javac.exe"
KEYTOOL="$JDK/bin/keytool.exe"
JAR="$JDK/bin/jar.exe"

AAR="${GV_AAR:-libs/geckoview-69.aar}"

# 版本号：v1.3.1 修广播菜单遮罩 + 节目单空档显示下一节目 + EPG 换新快照
# 注意：aapt2 link 的 --version-code/--version-name 不覆盖 AndroidManifest 里的声明，
#       要改版本号必须同时改 manifest，否则装上去还是旧版本号。
VER="${VER:-1.3.1}"
VERCODE="${VERCODE:-5}"

for f in "$PLATFORM" "$BT/aapt2.exe" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$JAVAC" "$AAR"; do
    [ -f "$f" ] || { echo "[ERROR] 缺少 $f"; exit 1; }
done

OUT="${BUILD_DIR:-build}"
APKDIR="${APK_DIR:-out}"
# 工具链是 Windows 原生 exe，路径要转成 C:/… 形式；bash 侧仍用 /c/… 形式。
abspath() { case "$1" in /*) printf '%s' "$1";; *) printf '%s/%s' "$(pwd)" "$1";; esac; }
PM_OUT="$(cygpath -m "$(abspath "$OUT")")"
PM_APKDIR="$(cygpath -m "$(abspath "$APKDIR")")"
PM_AAR="$(cygpath -m "$(abspath "$AAR")")"
rm -rf "$OUT" "$APKDIR"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/gv" "$APKDIR"

echo "[0/6] 解包 GeckoView AAR"
python - "$PM_AAR" "$PM_OUT/gv" <<'PY'
import sys, zipfile, os, shutil
aar, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(aar) as z:
    names = z.namelist()
    for n in names:
        if n == 'classes.jar':
            with z.open(n) as src, open(os.path.join(dst, 'classes.jar'), 'wb') as f:
                shutil.copyfileobj(src, f)
    libdir = os.path.join(dst, 'jni', 'armeabi-v7a')
    os.makedirs(libdir, exist_ok=True)
    so = [n for n in names if n.startswith('jni/armeabi-v7a/') and n.endswith('.so')]
    for n in so:
        with z.open(n) as src, open(os.path.join(libdir, os.path.basename(n)), 'wb') as f:
            shutil.copyfileobj(src, f)
    cnt = 0
    for n in names:
        if n.startswith('assets/') and not n.endswith('/'):
            p = os.path.join(dst, n)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with z.open(n) as src, open(p, 'wb') as f:
                shutil.copyfileobj(src, f)
            cnt += 1
    print('      classes.jar=%d MB  armv7 so=%d 个  assets=%d 个'
          % (os.path.getsize(os.path.join(dst, 'classes.jar')) // 1048576, len(so), cnt))
    for s in so:
        print('        %-28s %6.1f MB' % (os.path.basename(s),
              os.path.getsize(os.path.join(libdir, os.path.basename(s))) / 1048576.0))
PY

echo "[1/6] aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o "$PM_OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2.exe" link \
    -o "$PM_OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    -A assets \
    "$PM_OUT/res.zip" \
    --java "$PM_OUT/gen" \
    --min-sdk-version 24 --target-sdk-version 28 \
    --version-code "$VERCODE" --version-name "$VER"

echo "[3/6] javac"
find src stubs "$OUT/gen" -name "*.java" | while read -r f; do cygpath -m "$(abspath "$f")"; done > "$OUT/sources.txt"
CP="$PLATFORM;$PM_OUT/gv/classes.jar"
for j in libs/*.jar; do CP="$CP;$j"; done
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
    -classpath "$CP" \
    -d "$PM_OUT/classes" @"$PM_OUT/sources.txt"
"$JAR" cf "$PM_OUT/app.jar" -C "$PM_OUT/classes" .

echo "[4/6] d8（自动 multidex）"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 24 --lib "$PLATFORM" \
    --output "$PM_OUT/dex" "$PM_OUT/app.jar" "$PM_OUT/gv/classes.jar" libs/*.jar
ls -1 "$OUT"/dex/*.dex | sed 's/^/      /'

echo "[5/6] 组装 apk（dex + armv7 so + GeckoView assets）"
python - "$PM_OUT" <<'PY'
import shutil, zipfile, glob, os, sys
OUT = sys.argv[1]
shutil.copyfile(os.path.join(OUT, 'base.apk'), os.path.join(OUT, 'unsigned.apk'))
with zipfile.ZipFile(os.path.join(OUT, 'unsigned.apk'), 'a', zipfile.ZIP_DEFLATED) as z:
    for d in sorted(glob.glob(os.path.join(OUT, 'dex', '*.dex'))):
        z.write(d, os.path.basename(d))
        print('      %s' % os.path.basename(d))
    for so in sorted(glob.glob(os.path.join(OUT, 'gv', 'jni', 'armeabi-v7a', '*.so'))):
        z.write(so, 'lib/armeabi-v7a/' + os.path.basename(so), zipfile.ZIP_STORED)
        print('      lib/armeabi-v7a/%s (stored)' % os.path.basename(so))
    base = os.path.join(OUT, 'gv', 'assets')
    for root, _, files in os.walk(base):
        for fn in sorted(files):
            p = os.path.join(root, fn)
            rel = os.path.relpath(p, base).replace('\\', '/')
            z.write(p, 'assets/' + rel, zipfile.ZIP_STORED)
            print('      assets/%s (stored)' % rel)
PY

"$BT/zipalign.exe" -f -p 4 "$PM_OUT/unsigned.apk" "$PM_OUT/aligned.apk"

echo "[6/6] 签名"
KS="keystore/debug.keystore"
# 首次构建会现生成一个 debug 签名；keytool 不会自己建目录，缺了会静默失败
mkdir -p "$(dirname "$KS")"
if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
APK="$PM_APKDIR/LizhiGecko-$VER.apk"
"$JAVA" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:android \
    --key-pass pass:android --out "$APK" "$PM_OUT/aligned.apk"
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --verbose "$APK" > "$OUT/verify.txt"

echo
echo "构建完成：$APK"
ls -lh "$APK"
