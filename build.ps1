# Nothing Radio Widget - build script (Windows PowerShell)
# Requires: JDK 17 + Android SDK build-tools 34.0.0 + platforms/android-34
# Usage: .\build.ps1
# NOTE: keep this file ASCII-only; Windows PowerShell reads .ps1 as the
# system default encoding (GBK), so non-ASCII strings would garble parsing.

$ErrorActionPreference = "Stop"

# --- locate Android SDK ---
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = "C:\Android" }
if (-not (Test-Path "$sdk\build-tools\34.0.0\aapt2.exe")) {
    throw "Android SDK build-tools 34.0.0 not found. Set ANDROID_SDK_ROOT or ANDROID_HOME."
}

$BT = "$sdk\build-tools\34.0.0"
$PLATFORM = "$sdk\platforms\android-34\android.jar"
if (-not (Test-Path $PLATFORM)) {
    throw "android.jar not found. Install platforms/android-34."
}

$PROJ = $PSScriptRoot
$OUT = "$PROJ\build"

Write-Host "===== clean old outputs =====" -ForegroundColor Cyan
Remove-Item "$OUT\compiled","$OUT\gen","$OUT\classes","$OUT\dex" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$OUT\compiled","$OUT\gen","$OUT\classes","$OUT\dex" | Out-Null

Write-Host "===== 1. aapt2 compile resources =====" -ForegroundColor Cyan
& "$BT\aapt2.exe" compile --dir "$PROJ\res" -o "$OUT\compiled\res.zip"

Write-Host "===== 2. aapt2 link (pack assets fonts) =====" -ForegroundColor Cyan
& "$BT\aapt2.exe" link -o "$OUT\base.apk" -I $PLATFORM --manifest "$PROJ\AndroidManifest.xml" `
    -R "$OUT\compiled\res.zip" --java "$OUT\gen" -A "$PROJ\assets" --auto-add-overlay `
    --min-sdk-version 23 --target-sdk-version 31 --version-code 6 --version-name "2.2.0"

Write-Host "===== 3. javac compile Java =====" -ForegroundColor Cyan
$srcs = (Get-ChildItem "$PROJ\src" -Recurse -Filter *.java).FullName
$rjava = (Get-ChildItem "$OUT\gen" -Recurse -Filter R.java).FullName
# javac writes deprecation notes to stderr -> spurious NativeCommandError with Stop;
# lower it just for this call, then check the real exit code.
$prevPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& javac -encoding UTF-8 -classpath $PLATFORM -d "$OUT\classes" (@($srcs) + @($rjava)) 2>$null
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $prevPref
if ($javacExit -ne 0) { throw "javac failed with exit code $javacExit" }

Write-Host "===== 4. d8 to dex =====" -ForegroundColor Cyan
$classes = (Get-ChildItem "$OUT\classes" -Recurse -Filter *.class).FullName
& "$BT\d8.bat" --release --lib $PLATFORM --output "$OUT\dex" $classes

Write-Host "===== 5. pack dex into APK =====" -ForegroundColor Cyan
Copy-Item "$OUT\base.apk" "$OUT\unsigned.apk" -Force
Push-Location "$OUT\dex"
& jar -uf "$OUT\unsigned.apk" classes.dex
Pop-Location

Write-Host "===== 6. zipalign =====" -ForegroundColor Cyan
& "$BT\zipalign.exe" -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk"

Write-Host "===== 7. keystore (if missing) + sign =====" -ForegroundColor Cyan
if (-not (Test-Path "$OUT\debug.keystore")) {
    # keytool writes a progress line to stderr; with ErrorActionPreference=Stop
    # that becomes a spurious NativeCommandError, so lower it just for this call.
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & keytool -genkeypair -keystore "$OUT\debug.keystore" -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>$null | Out-Null
    $ErrorActionPreference = $prevPref
}
& "$BT\apksigner.bat" sign --ks "$OUT\debug.keystore" --ks-pass pass:android `
    --key-pass pass:android --ks-key-alias androiddebugkey `
    --out "$OUT\radiowidget.apk" "$OUT\aligned.apk"

Write-Host ""
Write-Host "BUILD OK: $OUT\radiowidget.apk" -ForegroundColor Green
