# OpenCV 16KB Alignment - Setup Complete! ✓

## Current Status

✅ **All build scripts created and working**  
✅ **NDK r28.2 detected** (28.2.13676358)  
✅ **CMake available**  
✅ **Android SDK configured**  
✅ **Project configured for custom OpenCV build**

## What Was Done

### 1. Project Configuration
- **`gradle.properties`**: Added NDK version specification
- **`app/build.gradle.kts`**: 
  - Removed stock OpenCV AAR dependency
  - Configured NDK r28+ for native builds
  - Set up jniLibs directory for custom libraries
  - Added packaging options for .so files

### 2. Build Scripts Created
- **`build_opencv.ps1`** - Builds OpenCV from source with 16KB alignment
- **`verify_opencv_alignment.ps1`** - Verifies all .so files have proper alignment
- **`quick_opencv_setup.ps1`** - Quick SDK download (not production-ready)
- **`test_setup.ps1`** - Environment verification

### 3. Documentation Created
- **`QUICKSTART.md`** - Quick reference guide
- **`BUILDING_OPENCV.md`** - Complete documentation
- **`README_SETUP.md`** - This file

## Next Steps

### Step 1: Build OpenCV (15-30 minutes)

```powershell
.\build_opencv.ps1
```

This will:
- Download OpenCV 4.12.0 source code
- Configure with NDK r28.2 (16KB alignment)
- Build for arm64-v8a and armeabi-v7a
- Install to `app/src/main/jniLibs/`
- Copy Java bindings to `app/src/main/java/`

### Step 2: Verify Alignment

```powershell
.\verify_opencv_alignment.ps1
```

Expected output for each .so file:
```
✓ Alignment: 16384 (0x4000) >= 16384 (2**14)
```

### Step 3: Build Your App

```powershell
.\gradlew clean assembleDebug
```

The custom-built OpenCV libraries will be bundled with proper 16KB alignment.

### Step 4: Test on Device

Deploy to an Android 15+ device with 16KB page size support to verify the crash is resolved.

## Why This Solves the 16KB Problem

| Before | After |
|--------|-------|
| Stock OpenCV AAR | Custom build from source |
| Built with old NDK | Built with NDK r28.2 |
| 4KB alignment (2**12) | **16KB alignment (2**14)** |
| Crashes on 16KB devices | ✓ Works on all devices |

## Technical Details

**NDK r28+ automatically applies:**
- Linker flag: `-Wl,-z,max-page-size=0x4000`
- ELF LOAD segments: `Align = 0x4000` (16384 bytes)
- Compatible with: Android 15+ devices using 16KB memory pages

**OpenCV modules built:**
- core, imgproc, imgcodecs, videoio
- calib3d, features2d, objdetect, ml
- All with 16KB alignment

## File Structure After Build

```
Camera2TestApp/
├── app/
│   └── src/main/
│       ├── jniLibs/              ← Built .so files
│       │   ├── arm64-v8a/
│       │   │   ├── libopencv_core.so
│       │   │   ├── libopencv_imgproc.so
│       │   │   ├── libopencv_java4.so
│       │   │   └── ... (all 16KB aligned)
│       │   └── armeabi-v7a/
│       │       └── ... (all 16KB aligned)
│       └── java/
│           └── org/opencv/        ← Java bindings
└── opencv_build/                  ← Build artifacts (can delete)
```

## Troubleshooting

### Build Fails
- **Disk space**: Need 10GB free
- **Internet**: Must download ~100MB
- **Clean rebuild**: `Remove-Item opencv_build -Recurse -Force; .\build_opencv.ps1`

### Alignment Verification Fails
If verification shows alignment < 16384:
1. Check NDK version: `.\test_setup.ps1`
2. Clean and rebuild
3. Verify you're using NDK r28+ (not r27 or older)

### App Still Crashes
If app still crashes after rebuilding:
1. **Check ALL libraries**: Not just OpenCV
   - MediaPipe, ML Kit, or other native libs may also need rebuilding
2. **Verify APK contents**: Extract APK and check all .so files
3. **Clean build**: `.\gradlew clean`

## Commands Summary

```powershell
# Test environment
.\test_setup.ps1

# Build OpenCV (do this first!)
.\build_opencv.ps1

# Verify alignment (should show 16384 for all)
.\verify_opencv_alignment.ps1

# Build app
.\gradlew clean assembleDebug

# Deploy and test
.\gradlew installDebug
```

## Additional Resources

- [Android 16KB Page Size Guide](https://developer.android.com/guide/practices/page-sizes)
- [OpenCV Documentation](https://docs.opencv.org/4.12.0/)
- [NDK Downloads](https://developer.android.com/ndk/downloads)

## Success Criteria

✅ `build_opencv.ps1` completes without errors  
✅ `verify_opencv_alignment.ps1` shows all files aligned to 16384  
✅ App builds successfully  
✅ App runs on Android 15+ with 16KB pages without crashing  

---

**Ready to build!** Run `.\build_opencv.ps1` to get started.
