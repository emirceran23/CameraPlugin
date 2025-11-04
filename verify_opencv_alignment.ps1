#!/usr/bin/env pwsh
# Verify OpenCV .so files have 16KB (2**14) alignment
# This verifies NDK r28+ produced correctly aligned libraries

param(
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$NdkVersion = "28.0.12433566",
    [string]$JniLibsDir = "app\src\main\jniLibs"
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

Write-ColorOutput Green "=== Verifying OpenCV Library Alignment ==="
Write-ColorOutput Cyan "Checking for 16KB (2**14) alignment required by Android 15+"

# Find NDK
if (-not $AndroidSdkRoot) {
    $AndroidSdkRoot = "C:\Users\ITEMS\AppData\Local\Android\Sdk"
}

$NdkPath = Join-Path $AndroidSdkRoot "ndk\$NdkVersion"
if (-not (Test-Path $NdkPath)) {
    Write-ColorOutput Red "Error: NDK $NdkVersion not found at $NdkPath"
    exit 1
}

# Find readelf tool
$ReadElfPaths = @(
    "$NdkPath\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe",
    "$NdkPath\toolchains\llvm\prebuilt\windows\bin\llvm-readelf.exe"
)

$ReadElf = $null
foreach ($Path in $ReadElfPaths) {
    if (Test-Path $Path) {
        $ReadElf = $Path
        break
    }
}

if (-not $ReadElf) {
    Write-ColorOutput Red "Error: llvm-readelf not found in NDK"
    Write-ColorOutput Yellow "Searched paths:"
    $ReadElfPaths | ForEach-Object { Write-ColorOutput Yellow "  $_" }
    exit 1
}

Write-ColorOutput Green "✓ Found llvm-readelf: $ReadElf"

# Check all .so files
$JniLibsPath = Join-Path $PSScriptRoot $JniLibsDir
if (-not (Test-Path $JniLibsPath)) {
    Write-ColorOutput Red "Error: jniLibs directory not found at $JniLibsPath"
    Write-ColorOutput Yellow "Please run build_opencv.ps1 first"
    exit 1
}

$AllPassed = $true
$FileCount = 0

Write-ColorOutput Cyan "`nScanning for .so files...`n"

Get-ChildItem -Path $JniLibsPath -Filter "*.so" -Recurse | ForEach-Object {
    $FileCount++
    $RelativePath = $_.FullName.Substring($JniLibsPath.Length + 1)
    
    Write-Host "Checking: " -NoNewline
    Write-Host $RelativePath -ForegroundColor Cyan
    
    # Run readelf to get program headers
    $Output = & $ReadElf -l $_.FullName 2>&1 | Out-String
    
    # Look for LOAD segments and their alignment
    $LoadSegments = $Output -split "`n" | Where-Object { $_ -match "LOAD" }
    
    if ($LoadSegments) {
        foreach ($Segment in $LoadSegments) {
            # Parse alignment from readelf output
            # Format: LOAD offset align
            if ($Segment -match "0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)\s*$") {
                $AlignHex = $matches[2]
                $AlignValue = [Convert]::ToInt64($AlignHex, 16)
                
                # Check if alignment is 2**14 (16384 = 0x4000)
                $Expected = 16384  # 2**14
                
                if ($AlignValue -ge $Expected) {
                    Write-ColorOutput Green "  ✓ Alignment: $AlignValue (0x$AlignHex) >= $Expected (2**14)"
                }
                else {
                    Write-ColorOutput Red "  ✗ Alignment: $AlignValue (0x$AlignHex) < $Expected (2**14)"
                    $AllPassed = $false
                }
            }
        }
    }
    else {
        # Try alternative parsing method
        if ($Output -match "Align:\s+0x([0-9a-fA-F]+)") {
            $AlignHex = $matches[1]
            $AlignValue = [Convert]::ToInt64($AlignHex, 16)
            $Expected = 16384
            
            if ($AlignValue -ge $Expected) {
                Write-ColorOutput Green "  ✓ Alignment: $AlignValue (0x$AlignHex) >= $Expected (2**14)"
            }
            else {
                Write-ColorOutput Red "  ✗ Alignment: $AlignValue (0x$AlignHex) < $Expected (2**14)"
                $AllPassed = $false
            }
        }
        else {
            Write-ColorOutput Yellow "  ! Could not parse alignment information"
            Write-ColorOutput Yellow "    Raw output:"
            $Output -split "`n" | Select-Object -First 20 | ForEach-Object {
                Write-ColorOutput Yellow "    $_"
            }
        }
    }
    
    Write-Host ""
}

Write-ColorOutput Cyan "───────────────────────────────────────"
Write-ColorOutput Cyan "Total files checked: $FileCount"

if ($FileCount -eq 0) {
    Write-ColorOutput Yellow "⚠ No .so files found to verify"
    Write-ColorOutput Yellow "Please run build_opencv.ps1 first to build OpenCV"
    exit 1
}

if ($AllPassed) {
    Write-ColorOutput Green "`n✓ SUCCESS: All libraries have proper 16KB alignment!"
    Write-ColorOutput Green "Your OpenCV build is ready for Android 15+ devices."
    exit 0
}
else {
    Write-ColorOutput Red "`n✗ FAILURE: Some libraries do not have proper alignment"
    Write-ColorOutput Red "Please rebuild OpenCV with NDK r28+ to fix this issue"
    exit 1
}
