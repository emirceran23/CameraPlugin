#!/usr/bin/env pwsh
# Build OpenCV 4.12.x from source with NDK r26+ for 16KB alignment

param(
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$NdkVersion = '28.2.13676358',
    [string]$OpenCvVersion = '4.12.0',
    [string]$BuildDir = 'opencv_build',
    [switch]$SkipDownload
)

$ErrorActionPreference = 'Stop'

function Write-ColorOutput {
    param(
        [Parameter(Mandatory)][ConsoleColor]$ForegroundColor,
        [Parameter(Mandatory)][string]$Message
    )
    $prev = $host.UI.RawUI.ForegroundColor
    try { $host.UI.RawUI.ForegroundColor = $ForegroundColor; Write-Host $Message }
    finally { $host.UI.RawUI.ForegroundColor = $prev }
}

Write-ColorOutput Green ('=== Building OpenCV {0} with NDK {1} ===' -f $OpenCvVersion, $NdkVersion)
Write-ColorOutput Green 'This build will produce 16-KB aligned .so files for Android 15+'

# --- 1. Validate Android SDK and NDK paths ---
if (-not $AndroidSdkRoot) {
    $defaultSdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    Write-ColorOutput Yellow ('ANDROID_SDK_ROOT not set. Trying default path: {0}' -f $defaultSdkPath)
    $AndroidSdkRoot = $defaultSdkPath
}

if (-not (Test-Path -LiteralPath $AndroidSdkRoot)) {
    Write-ColorOutput Red   ('Error: Android SDK not found at ''{0}''' -f $AndroidSdkRoot)
    Write-ColorOutput Yellow 'Please set ANDROID_SDK_ROOT or pass -AndroidSdkRoot.'
    exit 1
}

$NdkPath = Join-Path $AndroidSdkRoot ('ndk\{0}' -f $NdkVersion)
if (-not (Test-Path -LiteralPath $NdkPath)) {
    Write-ColorOutput Red   ('Error: NDK version {0} not found at ''{1}''' -f $NdkVersion, $NdkPath)
    Write-ColorOutput Yellow 'Install via Android Studio SDK Manager or:'
    Write-ColorOutput Yellow ("  sdkmanager --install 'ndk;{0}'" -f $NdkVersion)
    exit 1
}
Write-ColorOutput Green ('[OK] Found NDK at: {0}' -f $NdkPath)

# --- 2. Create a clean build directory ---
$BuildPath = Join-Path $PSScriptRoot $BuildDir
if (Test-Path -LiteralPath $BuildPath) {
    if ($SkipDownload.IsPresent) {
        Write-ColorOutput Yellow ("Build directory '{0}' exists. Reusing it..." -f $BuildDir)
    } else {
        Write-ColorOutput Yellow ("Build directory '{0}' exists. Cleaning it..." -f $BuildDir)
        Remove-Item -LiteralPath $BuildPath -Recurse -Force
        New-Item -ItemType Directory -Path $BuildPath | Out-Null
    }
} else {
    New-Item -ItemType Directory -Path $BuildPath | Out-Null
}

# --- 3. Download and Extract OpenCV source ---
$OpenCvSource = Join-Path $BuildPath ('opencv-{0}' -f $OpenCvVersion)
if (-not $SkipDownload.IsPresent -or -not (Test-Path -LiteralPath $OpenCvSource)) {
    Write-ColorOutput Cyan   ('Downloading OpenCV {0}...' -f $OpenCvVersion)
    $OpenCvUrl = 'https://github.com/opencv/opencv/archive/refs/tags/{0}.zip' -f $OpenCvVersion
    $OpenCvZip = Join-Path $BuildPath 'opencv.zip'
    try {
        Invoke-WebRequest -Uri $OpenCvUrl -OutFile $OpenCvZip -UseBasicParsing
        Write-ColorOutput Green '[OK] Download complete.'
        Write-ColorOutput Cyan  'Extracting OpenCV source...'
        Expand-Archive -Path $OpenCvZip -DestinationPath $BuildPath -Force
        Write-ColorOutput Green '[OK] Extraction complete.'
        Remove-Item -LiteralPath $OpenCvZip -Force
    } catch {
        Write-ColorOutput Red ('An error occurred during OpenCV download or extraction: {0}' -f $_)
        exit 1
    }
} else {
    Write-ColorOutput Yellow 'Skipping download as requested. Using existing source.'
}

# --- 4. Build for each specified ABI ---
$Abis = @('arm64-v8a', 'armeabi-v7a')
$OutputDir = Join-Path $PSScriptRoot 'app\src\main\jniLibs'

foreach ($Abi in $Abis) {
    Write-ColorOutput Green ('{0}=== Building for ABI: {1} ===' -f [Environment]::NewLine, $Abi)

    $BuildAbiPath = Join-Path $BuildPath ('build_{0}' -f $Abi)
    New-Item -ItemType Directory -Path $BuildAbiPath -Force | Out-Null

    Push-Location $BuildAbiPath
    try {
        # Find Ninja from Android SDK CMake
        $CmakeDir = Join-Path $AndroidSdkRoot 'cmake'
        $NinjaExe = $null
        if (Test-Path $CmakeDir) {
            $NinjaExe = Get-ChildItem -Path $CmakeDir -Filter 'ninja.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
        }
        
        # Determine generator
        $Generator = 'Ninja'
        $MakeProgram = $null
        
        if ($NinjaExe -and (Test-Path $NinjaExe)) {
            Write-ColorOutput Cyan "Using CMake generator: Ninja (found at $NinjaExe)"
            $MakeProgram = $NinjaExe
        } else {
            Write-ColorOutput Yellow "Ninja not found, using Unix Makefiles generator"
            $Generator = 'Unix Makefiles'
            # Try to find make from NDK
            $MakeExe = Join-Path $NdkPath 'prebuilt\windows-x86_64\bin\make.exe'
            if (Test-Path $MakeExe) {
                $MakeProgram = $MakeExe
            }
        }
        
        $CmakeArgs = @(
            '-G', $Generator
        )
        
        if ($MakeProgram) {
            $CmakeArgs += "-DCMAKE_MAKE_PROGRAM=$MakeProgram"
        }
        
        $CmakeArgs += @(
            ('-DCMAKE_TOOLCHAIN_FILE={0}\build\cmake\android.toolchain.cmake' -f $NdkPath),
            ('-DANDROID_ABI={0}' -f $Abi),
            '-DANDROID_PLATFORM=android-30',
            ('-DANDROID_NDK={0}' -f $NdkPath),
            '-DANDROID_STL=c++_shared',
            '-DCMAKE_BUILD_TYPE=Release',
            '-DCMAKE_INSTALL_PREFIX=install',

            # 16KB page alignment for Android 15+
            '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=65536',

            # OpenCV options
            '-DBUILD_SHARED_LIBS=ON',
            '-DBUILD_JAVA=ON',

            '-DBUILD_ANDROID_EXAMPLES=OFF',
            '-DBUILD_ANDROID_PROJECTS=OFF',
            '-DBUILD_TESTS=OFF',
            '-DBUILD_PERF_TESTS=OFF',
            '-DBUILD_DOCS=OFF',
            '-DBUILD_opencv_apps=OFF',
            '-DBUILD_opencv_python2=OFF',
            '-DBUILD_opencv_python3=OFF',

            '-DBUILD_opencv_core=ON',
            '-DBUILD_opencv_imgproc=ON',
            '-DBUILD_opencv_imgcodecs=ON',
            '-DBUILD_opencv_videoio=ON',
            '-DBUILD_opencv_calib3d=ON',
            '-DBUILD_opencv_features2d=ON',
            '-DBUILD_opencv_objdetect=ON',
            '-DBUILD_opencv_ml=ON',

            $OpenCvSource
        )

        Write-ColorOutput Cyan 'Running CMake configuration...'
        & cmake $CmakeArgs
        if ($LASTEXITCODE -ne 0) { throw ('CMake configuration failed for {0}' -f $Abi) }

        Write-ColorOutput Cyan ('Building OpenCV for {0}...' -f $Abi)
        $jobs = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
        if ($Generator -eq 'Ninja') {
            & cmake --build . --config Release -- -j $jobs
        } else {
            & cmake --build . --config Release -- -j$jobs
        }
        if ($LASTEXITCODE -ne 0) { throw ('Build failed for {0}' -f $Abi) }

        Write-ColorOutput Cyan 'Installing OpenCV libraries...'
        & cmake --install .
        if ($LASTEXITCODE -ne 0) { throw ('Installation failed for {0}' -f $Abi) }

        $InstallLibDir = Join-Path $BuildAbiPath ('install\sdk\native\libs\{0}' -f $Abi)
        $DestDir       = Join-Path $OutputDir $Abi

        if (Test-Path -LiteralPath $InstallLibDir) {
            New-Item -ItemType Directory -Path $DestDir -Force | Out-Null
            Copy-Item -LiteralPath (Join-Path $InstallLibDir '*.so') -Destination $DestDir -Force
            Write-ColorOutput Green ("[OK] Copied native libraries to '{0}'" -f $DestDir)
        } else {
            Write-ColorOutput Yellow ("Warning: No libraries found at '{0}'" -f $InstallLibDir)
        }
    } catch {
        Write-ColorOutput Red ('A critical error occurred while building for {0}: {1}' -f $Abi, $_.Exception.Message)
        Pop-Location
        exit 1
    } finally {
        Pop-Location
    }
}

# --- 5. Copy Java bindings ---
Write-ColorOutput Cyan ([Environment]::NewLine + 'Copying Java bindings...')
$JavaSrcDir  = Join-Path $BuildPath 'build_arm64-v8a\install\sdk\java\src'
$JavaDestDir = Join-Path $PSScriptRoot 'app\src\main\java'

if (Test-Path -LiteralPath $JavaSrcDir) {
    New-Item -ItemType Directory -Path $JavaDestDir -Force | Out-Null
    Copy-Item -Path (Join-Path $JavaSrcDir '*') -Destination $JavaDestDir -Recurse -Force
    Write-ColorOutput Green '[OK] Copied Java bindings.'
} else {
    Write-ColorOutput Red 'Error: Could not find Java source files to copy.'
}

Write-ColorOutput Green ([Environment]::NewLine + '=== Build Complete! ===')
Write-ColorOutput Green ('OpenCV {0} has been successfully built with 16KB alignment.' -f $OpenCvVersion)
Write-ColorOutput Green ('Libraries are located in: {0}' -f $OutputDir)

Write-Host ""
Write-Host "Next Steps:" -ForegroundColor Yellow
Write-Host "  1. Run: .\verify_opencv_alignment.ps1" -ForegroundColor Cyan
Write-Host "  2. Build your app: .\gradlew clean assembleDebug" -ForegroundColor Cyan
Write-Host ""

