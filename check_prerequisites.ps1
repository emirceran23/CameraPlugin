# Check prerequisites for building OpenCV with 16KB alignment
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  OpenCV 16KB Alignment Build - Prerequisites Check" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$AllGood = $true

# Check Android SDK
Write-Host "`n[1/6] Checking Android SDK..." -ForegroundColor White
$AndroidSdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "C:\Users\ITEMS\AppData\Local\Android\Sdk" }
if (Test-Path $AndroidSdk) {
    Write-Host "  ✓ PASS" -ForegroundColor Green
    Write-Host "  Found at: $AndroidSdk" -ForegroundColor Gray
} else {
    Write-Host "  ✗ FAIL" -ForegroundColor Red
    Write-Host "  Android SDK not found" -ForegroundColor Yellow
    Write-Host "  Fix: Install Android Studio or set ANDROID_SDK_ROOT" -ForegroundColor Cyan
    $AllGood = $false
}

# Check NDK r28+
Write-Host "`n[2/6] Checking NDK r28+..." -ForegroundColor White
$NdkPath = Join-Path $AndroidSdk "ndk\28.0.12433566"
if (-not (Test-Path $NdkPath)) {
    $NdkDir = Join-Path $AndroidSdk "ndk"
    if (Test-Path $NdkDir) {
        $NdkVersions = Get-ChildItem $NdkDir -Directory | Where-Object { $_.Name -match "^28\." }
        if ($NdkVersions) {
            $NdkPath = $NdkVersions[0].FullName
        }
    }
}
if (Test-Path $NdkPath) {
    Write-Host "  ✓ PASS" -ForegroundColor Green
    Write-Host "  Found at: $NdkPath" -ForegroundColor Gray
} else {
    Write-Host "  ✗ FAIL" -ForegroundColor Red
    Write-Host "  NDK r28+ not installed" -ForegroundColor Yellow
    Write-Host "  Fix: sdkmanager --install 'ndk;28.0.12433566'" -ForegroundColor Cyan
    $AllGood = $false
}

# Check CMake
Write-Host "`n[3/6] Checking CMake..." -ForegroundColor White
$CmakeFound = $false
$CmakePath = ""
try {
    $CmakeCmd = Get-Command cmake -ErrorAction SilentlyContinue
    if ($CmakeCmd) {
        $CmakeFound = $true
        $CmakePath = $CmakeCmd.Source
    }
} catch {}

if (-not $CmakeFound) {
    $CmakeDir = Join-Path $AndroidSdk "cmake"
    if (Test-Path $CmakeDir) {
        $CmakeVersion = Get-ChildItem $CmakeDir -Directory | Select-Object -First 1
        if ($CmakeVersion) {
            $CmakeFound = $true
            $CmakePath = $CmakeVersion.FullName
        }
    }
}

if ($CmakeFound) {
    Write-Host "  ✓ PASS" -ForegroundColor Green
    Write-Host "  Found at: $CmakePath" -ForegroundColor Gray
} else {
    Write-Host "  ✗ FAIL" -ForegroundColor Red
    Write-Host "  CMake not found" -ForegroundColor Yellow
    Write-Host "  Fix: sdkmanager --install 'cmake;3.22.1'" -ForegroundColor Cyan
    $AllGood = $false
}

# Check PowerShell version
Write-Host "`n[4/6] Checking PowerShell..." -ForegroundColor White
if ($PSVersionTable.PSVersion.Major -ge 5) {
    Write-Host "  ✓ PASS" -ForegroundColor Green
    Write-Host "  Version: $($PSVersionTable.PSVersion)" -ForegroundColor Gray
} else {
    Write-Host "  ✗ FAIL" -ForegroundColor Red
    Write-Host "  PowerShell 5.1+ required" -ForegroundColor Yellow
    Write-Host "  Fix: Update Windows or install PowerShell 7+" -ForegroundColor Cyan
    $AllGood = $false
}

# Check disk space
Write-Host "`n[5/6] Checking disk space..." -ForegroundColor White
$Drive = (Get-Location).Drive.Name
$FreeSpace = (Get-PSDrive $Drive).Free / 1GB
if ($FreeSpace -ge 10) {
    Write-Host "  ✓ PASS" -ForegroundColor Green
    Write-Host "  Available: $([math]::Round($FreeSpace, 2)) GB" -ForegroundColor Gray
} else {
    Write-Host "  ✗ FAIL" -ForegroundColor Red
    Write-Host "  Need 10GB free, have $([math]::Round($FreeSpace, 2)) GB" -ForegroundColor Yellow
    Write-Host "  Fix: Free up disk space" -ForegroundColor Cyan
    $AllGood = $false
}

# Check internet connection
Write-Host "`n[6/6] Checking internet connection..." -ForegroundColor White
try {
    $Response = Test-Connection github.com -Count 1 -Quiet -ErrorAction SilentlyContinue
    if ($Response) {
        Write-Host "  ✓ PASS" -ForegroundColor Green
        Write-Host "  Connected (can reach github.com)" -ForegroundColor Gray
    } else {
        Write-Host "  ✗ FAIL" -ForegroundColor Red
        Write-Host "  No internet connection" -ForegroundColor Yellow
        Write-Host "  Fix: Connect to internet to download OpenCV source" -ForegroundColor Cyan
        $AllGood = $false
    }
} catch {
    Write-Host "  ✗ FAIL" -ForegroundColor Red
    Write-Host "  No internet connection" -ForegroundColor Yellow
    Write-Host "  Fix: Connect to internet to download OpenCV source" -ForegroundColor Cyan
    $AllGood = $false
}

# Summary
Write-Host "`n=================================================================" -ForegroundColor Cyan
if ($AllGood) {
    Write-Host "`n✓ SUCCESS: All prerequisites met!" -ForegroundColor Green
    Write-Host "`nYou're ready to build OpenCV with 16KB alignment." -ForegroundColor White
    Write-Host "`nNext steps:" -ForegroundColor White
    Write-Host "  1. Build OpenCV:  .\build_opencv.ps1" -ForegroundColor Cyan
    Write-Host "  2. Verify:        .\verify_opencv_alignment.ps1" -ForegroundColor Cyan
    Write-Host "  3. Build app:     .\gradlew assembleDebug" -ForegroundColor Cyan
    Write-Host "`nEstimated build time: 15-30 minutes" -ForegroundColor White
    exit 0
} else {
    Write-Host "`n✗ FAILURE: Some prerequisites missing" -ForegroundColor Red
    Write-Host "`nPlease fix the issues above before building OpenCV." -ForegroundColor Yellow
    Write-Host "`nMost common fixes:" -ForegroundColor White
    Write-Host "  - Install NDK:    sdkmanager --install 'ndk;28.0.12433566'" -ForegroundColor Cyan
    Write-Host "  - Install CMake:  sdkmanager --install 'cmake;3.22.1'" -ForegroundColor Cyan
    exit 1
}
Write-Host "=================================================================`n" -ForegroundColor Cyan
