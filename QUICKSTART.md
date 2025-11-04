# Quick Start: Building OpenCV with 16KB Alignment

This is the TL;DR version. See [BUILDING_OPENCV.md](BUILDING_OPENCV.md) for full details.

## Problem
Android 15+ devices with 16KB memory pages require libraries with `Align 2**14` (16384 bytes). The stock OpenCV AAR doesn't have this alignment.

## Solution
Build OpenCV 4.12.x from source using NDK r28+, which produces 16KB-aligned .so files by default.

## Steps

### 1. Install NDK r28+

Open PowerShell in this directory and run:

```powershell
# Check if NDK is installed
$ndkPath = "C:\Users\ITEMS\AppData\Local\Android\Sdk\ndk\28.0.12433566"
if (Test-Path $ndkPath) {
    Write-Host "✓ NDK r28 already installed" -ForegroundColor Green
} else {
    Write-Host "✗ Installing NDK r28..." -ForegroundColor Yellow
    & "C:\Users\ITEMS\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --install "ndk;28.0.12433566"
}
```

Or use Android Studio:
- **Tools → SDK Manager → SDK Tools tab**
- Check "Show Package Details"
- Find **NDK (Side by side)** → Check **28.0.12433566**
- Click **Apply**

### 2. Build OpenCV

```powershell
.\build_opencv.ps1
```

⏱️ Takes 15-30 minutes. Go grab coffee ☕

### 3. Verify Alignment

```powershell
.\verify_opencv_alignment.ps1
```

You should see:
```
✓ Alignment: 16384 (0x4000) >= 16384 (2**14)
```

### 4. Build Your App

```powershell
.\gradlew clean assembleDebug
```

## What Changed

### Before
```gradle
// Stock OpenCV AAR - wrong alignment
implementation("org.opencv:opencv:4.12.0")
```

### After
```gradle
// Custom-built OpenCV with NDK r28+ - correct alignment
// Libraries in app/src/main/jniLibs/ built from source
```

## Verification

Every OpenCV .so file should show:
- **Align = 0x4000** (16384 bytes)
- **2**14** alignment

This is what `verify_opencv_alignment.ps1` checks.

## Troubleshooting

### "NDK not found"
```powershell
sdkmanager --install "ndk;28.0.12433566"
```

### "CMake not found"
Install via Android Studio SDK Manager or:
```powershell
sdkmanager --install "cmake;3.22.1"
```

### Build takes too long / Want to test quickly
Use the quick setup (⚠️ NOT production-ready):
```powershell
.\quick_opencv_setup.ps1
```
This downloads the SDK but **won't have proper alignment**. Only for development testing.

### Still crashes on 16KB devices
1. Check ALL .so files in your APK (not just OpenCV)
2. Rebuild third-party native libraries with NDK r28+
3. Verify with:
```powershell
.\verify_opencv_alignment.ps1
```

## File Locations

```
📦 Camera2TestApp/
├── 🔨 build_opencv.ps1              ← Run this to build
├── ✅ verify_opencv_alignment.ps1   ← Run this to verify
├── 📖 BUILDING_OPENCV.md            ← Full documentation
├── 🚀 QUICKSTART.md                 ← This file
└── 📱 app/src/main/
    ├── jniLibs/                     ← Built .so files go here
    │   ├── arm64-v8a/
    │   │   ├── libopencv_core.so
    │   │   ├── libopencv_imgproc.so
    │   │   └── ...
    │   └── armeabi-v7a/
    └── java/                        ← Java bindings go here
        └── org/opencv/...
```

## Summary

| Step | Command | Time | Purpose |
|------|---------|------|---------|
| 1 | Install NDK r28+ | 2 min | Get toolchain with 16KB support |
| 2 | `.\build_opencv.ps1` | 20 min | Build aligned libraries |
| 3 | `.\verify_opencv_alignment.ps1` | 10 sec | Confirm 2**14 alignment |
| 4 | `.\gradlew assembleDebug` | 2 min | Build app with new libs |

**Total time**: ~25 minutes

## Success Criteria

✅ All .so files show `Align: 16384 (0x4000)`  
✅ App builds without errors  
✅ App runs on Android 15+ devices with 16KB pages  

## Resources

- 📚 [Full Build Guide](BUILDING_OPENCV.md)
- 🔗 [Android 16KB Guide](https://developer.android.com/guide/practices/page-sizes)
- 🔗 [OpenCV Docs](https://docs.opencv.org/4.12.0/)
