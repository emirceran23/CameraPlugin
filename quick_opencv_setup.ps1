#!/usr/bin/env pwsh
# Quick build of OpenCV using the Android SDK package with NDK r28+
# This is faster than building from complete source

param(
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$NdkVersion = "28.0.12433566",
    [string]$OpenCvVersion = "4.12.0"
)

$ErrorActionPreference = "Stop"

function Write-ColorOutput($ForegroundColor) {
    $fc = $host.UI.RawUI.ForegroundColor
    $host.UI.RawUI.ForegroundColor = $ForegroundColor
    if ($args) {
        Write-Output $args
    }
    $host.UI.RawUI.ForegroundColor = $fc
}

Write-ColorOutput Green "=== Quick OpenCV Setup with NDK r28+ ==="
Write-ColorOutput Cyan "This script downloads OpenCV Android SDK and rebuilds with NDK r28+"

# Validate Android SDK
if (-not $AndroidSdkRoot) {
    $AndroidSdkRoot = "C:\Users\ITEMS\AppData\Local\Android\Sdk"
}

if (-not (Test-Path $AndroidSdkRoot)) {
    Write-ColorOutput Red "Error: Android SDK not found at $AndroidSdkRoot"
    exit 1
}

$NdkPath = Join-Path $AndroidSdkRoot "ndk\$NdkVersion"
if (-not (Test-Path $NdkPath)) {
    Write-ColorOutput Red "Error: NDK $NdkVersion not found"
    Write-ColorOutput Yellow "Install with: sdkmanager --install 'ndk;$NdkVersion'"
    exit 1
}

Write-ColorOutput Green "✓ Found NDK at: $NdkPath"

# Download OpenCV Android SDK
$WorkDir = Join-Path $PSScriptRoot "opencv_sdk"
if (-not (Test-Path $WorkDir)) {
    New-Item -ItemType Directory -Path $WorkDir | Out-Null
}

$SdkDir = Join-Path $WorkDir "opencv-android-sdk"
$ZipFile = Join-Path $WorkDir "opencv-android.zip"

if (-not (Test-Path $SdkDir)) {
    Write-ColorOutput Cyan "Downloading OpenCV $OpenCvVersion Android SDK..."
    $Url = "https://github.com/opencv/opencv/releases/download/$OpenCvVersion/opencv-$OpenCvVersion-android-sdk.zip"
    
    try {
        Invoke-WebRequest -Uri $Url -OutFile $ZipFile -UseBasicParsing
        Write-ColorOutput Green "✓ Downloaded"
        
        Write-ColorOutput Cyan "Extracting..."
        Expand-Archive -Path $ZipFile -DestinationPath $WorkDir -Force
        
        # Find the extracted directory
        $ExtractedDirs = Get-ChildItem $WorkDir -Directory | Where-Object { $_.Name -like "OpenCV-android-sdk" }
        if ($ExtractedDirs) {
            Move-Item $ExtractedDirs[0].FullName $SdkDir -Force
        }
        
        Remove-Item $ZipFile -Force
        Write-ColorOutput Green "✓ Extracted SDK"
    }
    catch {
        Write-ColorOutput Red "Error: $_"
        exit 1
    }
}

# Copy native libraries and rebuild markers
$OutputDir = Join-Path $PSScriptRoot "app\src\main\jniLibs"
$SdkNativeDir = Join-Path $SdkDir "sdk\native\libs"

if (Test-Path $SdkNativeDir) {
    Write-ColorOutput Yellow "`n⚠ WARNING: OpenCV SDK libraries may not have 16KB alignment!"
    Write-ColorOutput Yellow "We'll copy them, but you MUST rebuild from source for proper alignment."
    Write-ColorOutput Yellow "Use build_opencv.ps1 for production builds."
    
    Write-ColorOutput Cyan "`nCopying SDK libraries temporarily..."
    
    foreach ($Abi in @("arm64-v8a", "armeabi-v7a")) {
        $SrcDir = Join-Path $SdkNativeDir $Abi
        $DstDir = Join-Path $OutputDir $Abi
        
        if (Test-Path $SrcDir) {
            New-Item -ItemType Directory -Path $DstDir -Force | Out-Null
            Copy-Item "$SrcDir\*.so" $DstDir -Force
            Write-ColorOutput Green "✓ Copied $Abi libraries"
        }
    }
}

# Copy Java bindings
$JavaSrc = Join-Path $SdkDir "sdk\java\src"
$JavaDst = Join-Path $PSScriptRoot "app\src\main\java"

if (Test-Path $JavaSrc) {
    Write-ColorOutput Cyan "Copying Java bindings..."
    Copy-Item "$JavaSrc\*" $JavaDst -Recurse -Force
    Write-ColorOutput Green "✓ Copied Java bindings"
}

Write-ColorOutput Yellow "`n" + ("=" * 60)
Write-ColorOutput Red "⚠ IMPORTANT: These libraries are NOT properly aligned!"
Write-ColorOutput Yellow ("=" * 60)
Write-ColorOutput Yellow "`nThe OpenCV SDK uses older NDK and won't have 16KB alignment."
Write-ColorOutput Yellow "For production use with Android 15+ (16KB pages), you MUST:"
Write-ColorOutput Yellow "  1. Run: .\build_opencv.ps1"
Write-ColorOutput Yellow "  2. Run: .\verify_opencv_alignment.ps1"
Write-ColorOutput Yellow "`nThis quick script is only for:"
Write-ColorOutput Yellow "  • Getting Java bindings quickly"
Write-ColorOutput Yellow "  • Testing on devices with 4KB pages"
Write-ColorOutput Yellow "  • Development before full rebuild"

Write-ColorOutput Cyan "`nNext steps:"
Write-ColorOutput Cyan "  .\build_opencv.ps1              # Proper 16KB-aligned build"
Write-ColorOutput Cyan "  .\verify_opencv_alignment.ps1   # Verify alignment"
