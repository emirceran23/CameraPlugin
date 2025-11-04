# Building OpenCV 4.12.x with NDK r28+ for 16KB Alignment

This guide explains how to build OpenCV from source with NDK r28+ to achieve proper 16KB page alignment required for Android 15+ devices.

## Why This Matters

Android 15+ supports devices with 16KB memory pages. Libraries must be properly aligned (Align 2**14 = 16384 bytes) or apps will crash. NDK r28+ produces correctly aligned .so files by default.

## Prerequisites

1. **Install NDK r28+** via Android Studio SDK Manager:
   - Open Android Studio → Settings → Android SDK → SDK Tools
   - Check "Show Package Details"
   - Find "NDK (Side by side)" and install version `28.0.12433566` or newer
   
   Or via command line:
   ```powershell
   sdkmanager --install "ndk;28.0.12433566"
   ```

2. **Install CMake** (if not already installed):
   ```powershell
   sdkmanager --install "cmake;3.22.1"
   ```

3. **Install Required Tools**:
   - PowerShell 5.1+ (comes with Windows)
   - Git (to download OpenCV source)
   - At least 10GB free disk space

## Building OpenCV

### Step 1: Build OpenCV from Source

Run the build script from the project root:

```powershell
.\build_opencv.ps1
```

This script will:
- Download OpenCV 4.12.0 source code
- Configure CMake with NDK r28+ toolchain
- Build OpenCV for `arm64-v8a` and `armeabi-v7a` architectures
- Install libraries to `app/src/main/jniLibs/`
- Copy Java bindings to `app/src/main/java/`

**Build time**: 15-30 minutes depending on your CPU

#### Build Options

```powershell
# Specify custom Android SDK location
.\build_opencv.ps1 -AndroidSdkRoot "C:\Android\Sdk"

# Use different NDK version
.\build_opencv.ps1 -NdkVersion "28.0.12345678"

# Use different OpenCV version
.\build_opencv.ps1 -OpenCvVersion "4.12.1"

# Skip re-downloading if source already exists
.\build_opencv.ps1 -SkipDownload
```

### Step 2: Verify 16KB Alignment

After building, verify that all .so files have proper alignment:

```powershell
.\verify_opencv_alignment.ps1
```

This script checks every OpenCV library and confirms:
- **✓ Alignment: 16384 (0x4000) >= 16384 (2**14)** ← GOOD
- **✗ Alignment: 4096 (0x1000) < 16384 (2**14)** ← BAD (need to rebuild)

All libraries should show **Align 2**14** (16384 bytes).

### Step 3: Build Your App

Once verification passes, rebuild your app:

```powershell
.\gradlew clean assembleDebug
```

The custom-built OpenCV libraries will be included in your APK with proper 16KB alignment.

## Project Configuration

The following files have been configured for NDK r28+ and custom OpenCV:

### gradle.properties
```properties
android.ndkVersion=28.0.12433566
```

### app/build.gradle.kts
- NDK version specified: `28.0.12433566`
- Native build configured for C++17 with `c++_shared` STL
- Stock OpenCV AAR dependency removed
- JNI libs sourced from `src/main/jniLibs`
- Packaging configured to handle native libraries correctly

## Troubleshooting

### NDK Not Found
```
Error: NDK 28.0.12433566 not found
```
**Solution**: Install NDK r28+ using Android Studio SDK Manager or:
```powershell
sdkmanager --install "ndk;28.0.12433566"
```

### CMake Not Found
```
cmake: command not found
```
**Solution**: Install CMake via SDK Manager or add it to PATH

### Build Fails
- Check you have at least 10GB free disk space
- Ensure you have a stable internet connection (for downloading OpenCV)
- Try cleaning and rebuilding:
  ```powershell
  Remove-Item opencv_build -Recurse -Force
  .\build_opencv.ps1
  ```

### Alignment Verification Fails
If `verify_opencv_alignment.ps1` reports alignment < 16384:
1. Ensure NDK version is actually r28+ (check `local.properties`)
2. Clean and rebuild OpenCV completely
3. Check that you're using the built libraries, not cached ones

### App Still Crashes on 16KB Devices
1. Verify ALL .so files in your APK (including third-party libraries):
   ```powershell
   # Extract APK
   Expand-Archive app-debug.apk -DestinationPath extracted
   
   # Check all .so files
   Get-ChildItem extracted\lib -Filter "*.so" -Recurse | ForEach-Object {
       llvm-readelf -l $_.FullName | Select-String "Align"
   }
   ```

2. If non-OpenCV libraries fail, they also need rebuilding with NDK r28+

## Technical Details

### What is 16KB Alignment?

- **Traditional**: Android uses 4KB memory pages (Align 2**12 = 4096)
- **New**: Some Android 15+ devices use 16KB pages (Align 2**14 = 16384)
- **Why**: Larger pages improve performance on modern ARM CPUs

### NDK r28+ Changes

NDK r28 automatically applies 16KB alignment flags during linking:
- `-Wl,-z,max-page-size=0x4000`
- Produces LOAD segments with Align = 0x4000 (16384)

### Verification Command

The verification script uses `llvm-readelf` to check ELF headers:
```bash
llvm-readelf -l libopencv_core.so
```

Look for `LOAD` segments with `Align = 0x4000` (16384 bytes).

## File Structure

```
Camera2TestApp/
├── build_opencv.ps1              # Build script
├── verify_opencv_alignment.ps1   # Verification script
├── BUILDING_OPENCV.md            # This file
├── gradle.properties             # NDK version configured
├── app/
│   ├── build.gradle.kts          # NDK and native build configured
│   └── src/main/
│       ├── jniLibs/              # OpenCV .so files (generated)
│       │   ├── arm64-v8a/
│       │   │   ├── libopencv_core.so
│       │   │   ├── libopencv_imgproc.so
│       │   │   └── ...
│       │   └── armeabi-v7a/
│       │       └── ...
│       └── java/                 # OpenCV Java bindings (generated)
│           └── org/opencv/...
└── opencv_build/                 # Build artifacts (can be deleted)
```

## Next Steps

1. **Build OpenCV**: `.\build_opencv.ps1`
2. **Verify alignment**: `.\verify_opencv_alignment.ps1`
3. **Build app**: `.\gradlew assembleDebug`
4. **Test on device**: Deploy to Android 15+ device with 16KB pages

## References

- [Android 16KB Page Size Guide](https://developer.android.com/guide/practices/page-sizes)
- [OpenCV Android Documentation](https://docs.opencv.org/4.12.0/d5/df8/tutorial_dev_with_OCV_on_Android.html)
- [NDK r28 Release Notes](https://developer.android.com/ndk/downloads)

## License

OpenCV is released under Apache 2.0 License. See [OpenCV License](https://opencv.org/license/).
