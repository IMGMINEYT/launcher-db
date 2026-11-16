set -e
set -o pipefail
export JAVA_HOME=~/apk/jdk-17.0.21+9; export PATH=$JAVA_HOME/bin:$PATH
SDK=~/apk/sdk; BT=$SDK/build-tools/34.0.0; AJ=$SDK/platforms/android-34/android.jar
cd ~/agent; rm -rf out; mkdir -p out/gen out/cls out/dex out/res
$BT/aapt2 compile --dir app/res -o out/res
$BT/aapt2 link -o out/base.apk -I $AJ --manifest app/AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 33 --version-code 12 --version-name 1.1 --java out/gen out/res/*.flat -A app/assets
MLJ=$(ls ~/apk/mlkit/x/jars/*.jar | tr '\n' ':')
javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$AJ:$MLJ" -d out/cls $(find out/gen mlkit-r app/src -name '*.java') 2>&1
$BT/d8 --min-api 24 --lib $AJ --output out/dex $(find out/cls -name '*.class') ~/apk/mlkit/x/jars/*.jar
cp out/base.apk out/un.apk; (cd out/dex && zip -q ../un.apk classes.dex)
$BT/zipalign -f 4 out/un.apk out/al.apk
KS=${AGENT_KEYSTORE:-~/agent/dev.keystore}
if [ ! -f $KS ]; then keytool -genkeypair -keystore $KS -storepass agenticdev -keypass agenticdev -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Agentic Dev"; fi
$BT/apksigner sign --ks $KS --ks-pass pass:agenticdev --key-pass pass:agenticdev --out out/Agentic.apk out/al.apk
$BT/apksigner verify out/Agentic.apk && ls -l out/Agentic.apk
