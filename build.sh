#!/bin/bash
# HaierAirRemote 命令行构建脚本 (依赖: JDK8+, build-tools 28.0.3, platform android-28)
set -e
BT=${BT:-/opt/android-sdk/build-tools/28.0.3}
ANDROID_JAR=${ANDROID_JAR:-/opt/android-sdk/platforms/android-28/android.jar}
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC=$ROOT/app/src/main
BUILD=$ROOT/build
KS=${1:-$BUILD/haier.keystore}

mkdir -p $BUILD/classes $BUILD/gen
echo "== aapt =="
$BT/aapt package -f -F $BUILD/base.apk -M $SRC/AndroidManifest.xml -S $SRC/res \
  -I $ANDROID_JAR -m -J $BUILD/gen --min-sdk-version 21 --target-sdk-version 28
echo "== javac =="
find $BUILD/gen -name "*.java" > /tmp/har_src.txt
find $SRC/java -name "*.java" >> /tmp/har_src.txt
javac -source 1.8 -target 1.8 -classpath $ANDROID_JAR -d $BUILD/classes @/tmp/har_src.txt
echo "== d8 =="
$BT/d8 --lib $ANDROID_JAR --min-api 21 --output $BUILD $(find $BUILD/classes -name "*.class")
echo "== pack =="
cp $BUILD/base.apk $BUILD/unsigned.apk
(cd $BUILD && zip -q -j unsigned.apk classes.dex)
$BT/zipalign -f 4 $BUILD/unsigned.apk $BUILD/aligned.apk
if [ ! -f "$KS" ]; then
  echo "== 生成新签名密钥 =="
  keytool -genkeypair -keystore $KS -alias haier -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass haier123 -keypass haier123 -dname "CN=HaierAirRemote"
fi
$BT/apksigner sign --ks $KS --ks-pass pass:haier123 --ks-key-alias haier --key-pass pass:haier123 \
  --out $BUILD/signed.apk $BUILD/aligned.apk
$BT/apksigner verify $BUILD/signed.apk
cp $BUILD/signed.apk $ROOT/apk/HaierAirRemote.apk
echo "完成: apk/HaierAirRemote.apk"
