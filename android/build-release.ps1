param(
    [Parameter(Mandatory = $true)][string]$PasswordFile,
    [string]$KeyStore = (Join-Path $env:LOCALAPPDATA "EXTC\signing\release.p12"),
    [string]$KeyAlias = "extc-release"
)

$ErrorActionPreference = "Continue"
$projectDirectory = $PSScriptRoot
if (!(Test-Path -LiteralPath $PasswordFile)) { throw "The private password file is missing." }
$sdkDirectory = $env:ANDROID_HOME
if (!$sdkDirectory) { $sdkDirectory = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$buildToolsDirectory = Join-Path $sdkDirectory "build-tools\36.0.0"
$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
if (!(Test-Path -LiteralPath $KeyStore)) {
    New-Item -ItemType Directory -Force (Split-Path -Parent $KeyStore) | Out-Null
    & $keytool -genkeypair -keystore $KeyStore -storetype PKCS12 -storepass:file $PasswordFile -alias $KeyAlias -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Experimental Compressor, O=krashkanter"
    if ($LASTEXITCODE -ne 0) { throw "Release signing key generation failed." }
}
& (Join-Path $projectDirectory "gradlew.bat") -p $projectDirectory assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Release build failed." }
$unsignedApk = Join-Path $projectDirectory "app\build\outputs\apk\release\app-release-unsigned.apk"
$alignedApk = Join-Path $projectDirectory "app\build\outputs\apk\release\app-release-aligned.apk"
$signedApk = Join-Path $projectDirectory "app\build\outputs\apk\release\experimental-compressor-0.2.0-release.apk"
& (Join-Path $buildToolsDirectory "zipalign.exe") -f -P 16 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "APK alignment failed." }
& (Join-Path $buildToolsDirectory "apksigner.bat") sign --ks $KeyStore --ks-key-alias $KeyAlias --ks-pass "file:$PasswordFile" --out $signedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "Release signing failed." }
& (Join-Path $buildToolsDirectory "apksigner.bat") verify --verbose $signedApk
if ($LASTEXITCODE -ne 0) { throw "Signature verification failed." }
& (Join-Path $buildToolsDirectory "zipalign.exe") -c -P 16 4 $signedApk
if ($LASTEXITCODE -ne 0) { throw "APK alignment verification failed." }
Write-Output "Release APK: $signedApk"
Write-Output "Signing key: $KeyStore"
