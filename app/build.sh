set -e
export JAVA_HOME=~/apk/jdk-17.0.21+9; export PATH=$JAVA_HOME/bin:$PATH
SDK=~/apk/sdk; BT=$SDK/build-tools/34.0.0; AJ=$SDK/platforms/android-34/android.jar
cd ~/apk; rm -rf out; mkdir -p out/gen out/cls out/dex out/res
$BT/aapt2 compile --dir app/res -o out/res
$BT/aapt2 link -o out/base.apk -I $AJ --manifest app/AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 33 --version-code 12 --version-name 2.2 --java out/gen out/res/*.flat -A app/assets
javac -nowarn -source 8 -target 8 -bootclasspath $AJ -d out/cls $(find out/gen app/src -name '*.java') 2>&1 | grep -v "^warning\|^Note" || true
test -d out/cls/com || { echo "JAVAC FAILED"; exit 1; }
$BT/d8 --min-api 21 --lib $AJ --output out/dex $(find out/cls -name '*.class')
cp out/base.apk out/un.apk; (cd out/dex && zip -q ../un.apk classes.dex)
$BT/zipalign -f 4 out/un.apk out/al.apk
[ -f ~/apk/67launcher.keystore ] || keytool -genkeypair -keystore ~/apk/67launcher.keystore -storepass 67launcher -keypass 67launcher -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=67 Launcher"
$BT/apksigner sign --ks ~/apk/67launcher.keystore --ks-pass pass:67launcher --key-pass pass:67launcher --out out/67-Launcher.apk out/al.apk
$BT/apksigner verify out/67-Launcher.apk && ls -l out/67-Launcher.apk
