# Simple test to verify build scripts are working
Write-Host "`n=== OpenCV Build Environment Test ===" -ForegroundColor Cyan

# Test 1: Check if scripts exist
Write-Host "`n[1] Checking if build scripts exist..." -ForegroundColor White
$scripts = @('build_opencv.ps1', 'verify_opencv_alignment.ps1', 'quick_opencv_setup.ps1')
foreach ($script in $scripts) {
    if (Test-Path $script) {
        Write-Host "  [OK] $script" -ForegroundColor Green
    } else {
        Write-Host "  [MISSING] $script" -ForegroundColor Red
    }
}

# Test 2: Check Android SDK
Write-Host "`n[2] Checking Android SDK..." -ForegroundColor White
$sdkPath = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "C:\Users\ITEMS\AppData\Local\Android\Sdk" }
if (Test-Path $sdkPath) {
    Write-Host "  [OK] Found at: $sdkPath" -ForegroundColor Green
} else {
    Write-Host "  [NOT FOUND] $sdkPath" -ForegroundColor Yellow
}

# Test 3: Check NDK
Write-Host "`n[3] Checking for NDK r28+..." -ForegroundColor White
$ndkBase = Join-Path $sdkPath "ndk"
if (Test-Path $ndkBase) {
    $ndkVersions = Get-ChildItem $ndkBase -Directory | Where-Object { $_.Name -match "^2[89]\." }
    if ($ndkVersions) {
        foreach ($ndk in $ndkVersions) {
            Write-Host "  [OK] Found: $($ndk.Name)" -ForegroundColor Green
        }
    } else {
        Write-Host "  [NOT FOUND] No NDK r28+ installed" -ForegroundColor Yellow
        Write-Host "  Install with: sdkmanager --install 'ndk;28.0.12433566'" -ForegroundColor Cyan
    }
} else {
    Write-Host "  [NOT FOUND] NDK directory does not exist" -ForegroundColor Yellow
}

# Test 4: Check CMake
Write-Host "`n[4] Checking CMake..." -ForegroundColor White
$cmakeFound = $false
try {
    $cmake = Get-Command cmake -ErrorAction SilentlyContinue
    if ($cmake) {
        Write-Host "  [OK] Found in PATH: $($cmake.Source)" -ForegroundColor Green
        $cmakeFound = $true
    }
} catch {}

if (-not $cmakeFound) {
    $cmakeDir = Join-Path $sdkPath "cmake"
    if (Test-Path $cmakeDir) {
        $cmakeVer = Get-ChildItem $cmakeDir -Directory | Select-Object -First 1
        if ($cmakeVer) {
            Write-Host "  [OK] Found in SDK: $($cmakeVer.FullName)" -ForegroundColor Green
            $cmakeFound = $true
        }
    }
}

if (-not $cmakeFound) {
    Write-Host "  [NOT FOUND] CMake not available" -ForegroundColor Yellow
    Write-Host "  Install with: sdkmanager --install 'cmake;3.22.1'" -ForegroundColor Cyan
}

# Summary
Write-Host "`n=== Summary ===" -ForegroundColor Cyan
Write-Host "`nTo build OpenCV with 16KB alignment:" -ForegroundColor White
Write-Host "  1. Install NDK r28+ (if not already installed)" -ForegroundColor Cyan
Write-Host "  2. Run: .\build_opencv.ps1" -ForegroundColor Cyan
Write-Host "  3. Run: .\verify_opencv_alignment.ps1" -ForegroundColor Cyan
Write-Host "  4. Build your app: .\gradlew assembleDebug" -ForegroundColor Cyan
Write-Host ""
