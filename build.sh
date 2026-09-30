#!/bin/bash
set -e
export JAVA_HOME=$HOME/jdk/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH
SDK=$HOME/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
PROJ=$HOME/workspace/greenroom-android
OUT=$PROJ/out
APP_VERSION="1.11.3"   # bump per release; versionCode auto-increments below
rm -rf $OUT && mkdir -p $OUT/compiled $OUT/classes

# Android refuses to install an APK whose versionCode isn't HIGHER than the
# installed one. Bump it on every build so updates always apply.
MF=$PROJ/AndroidManifest.xml
VC=$(grep -oP 'android:versionCode="\K[0-9]+' "$MF" || echo 9)
NV=$((VC+1))
sed -i -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$NV\"/" "$MF"
sed -i -E "s/android:versionName=\"[^\"]*\"/android:versionName=\"$APP_VERSION\"/" "$MF"
echo "== version $APP_VERSION (code $NV) =="

# Stage fresh web assets every build (the app bundles this page; a stale copy
# here means the APK ships old code even when the site is current).
cp ~/workspace/nostr-agent-chat/web/index.html $PROJ/assets/index.html
cp ~/workspace/nostr-agent-chat/web/secp256k1.bundle.js $PROJ/assets/secp256k1.bundle.js
echo "== assets staged =="

echo "== aapt2 compile =="
$BT/aapt2 compile --dir $PROJ/res -o $OUT/compiled_res.zip

echo "== aapt2 link =="
$BT/aapt2 link -o $OUT/base.apk \
  -I $PLATFORM \
  --manifest $PROJ/AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 34 \
  -A $PROJ/assets \
  --java $OUT/gen \
  $OUT/compiled_res.zip

echo "== javac =="
mkdir -p $OUT/gen/com/greenroom/app
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $PLATFORM \
  -cp "$PROJ/libs/*" \
  -d $OUT/classes \
  $(find $OUT/gen $PROJ/java -name "*.java")

echo "== d8 =="
mkdir -p $OUT/dex
$BT/d8 --lib $PLATFORM --output $OUT/dex $(find $OUT/classes -name "*.class") $PROJ/libs/*.jar

echo "== add dex, align, sign =="
cp $OUT/base.apk $OUT/app-unsigned.apk
(cd $OUT/dex && zip -q -X $OUT/app-unsigned.apk classes.dex)
[ -f $HOME/.android/debug.keystore ] || keytool -genkeypair -keystore $HOME/.android/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
$BT/zipalign -f 4 $OUT/app-unsigned.apk $OUT/app-aligned.apk
$BT/apksigner sign --ks $HOME/.android/debug.keystore --ks-pass pass:android \
  --out $PROJ/greenroom.apk $OUT/app-aligned.apk
$BT/apksigner verify --print-certs $PROJ/greenroom.apk | head -4
ls -la $PROJ/greenroom.apk
