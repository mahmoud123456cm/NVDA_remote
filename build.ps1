# Windows PowerShell. Requires JDK 17:  winget install EclipseAdoptium.Temurin.17.JDK
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Write-Host "Install JDK 17 first: winget install EclipseAdoptium.Temurin.17.JDK"; exit 1 }
$sdk = Join-Path $env:USERPROFILE "android-sdk"
$sm = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sm)) {
  New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
  Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile "$env:TEMP\clt.zip"
  Expand-Archive "$env:TEMP\clt.zip" "$sdk\cmdline-tools" -Force
  if (Test-Path "$sdk\cmdline-tools\latest") { Remove-Item "$sdk\cmdline-tools\latest" -Recurse -Force }
  Rename-Item "$sdk\cmdline-tools\cmdline-tools" "latest"
}
$env:ANDROID_HOME = $sdk
("y`n" * 30) | & $sm --licenses | Out-Null
& $sm "platforms;android-34" "build-tools;34.0.0" "platform-tools"
"sdk.dir=" + $sdk.Replace("\", "\\") | Set-Content local.properties
$g = Join-Path $env:USERPROFILE "gradle-8.7"
if (-not (Test-Path $g)) {
  Invoke-WebRequest "https://services.gradle.org/distributions/gradle-8.7-bin.zip" -OutFile "$env:TEMP\g.zip"
  Expand-Archive "$env:TEMP\g.zip" $env:USERPROFILE -Force
}
& "$g\bin\gradle.bat" --no-daemon assembleDebug
Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "NvdaPocket.apk" -Force
Write-Host "Done: $PSScriptRoot\NvdaPocket.apk"
