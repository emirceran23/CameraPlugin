# Monitor OpenCV Build Progress
# Run this in a separate terminal while build_opencv.ps1 is running

$buildDir = "opencv_build"

Write-Host "`n=== OpenCV Build Monitor ===" -ForegroundColor Cyan
Write-Host "Monitoring build progress...`n" -ForegroundColor Yellow

$lastStatus = ""
$startTime = Get-Date

while ($true) {
    Clear-Host
    Write-Host "=== OpenCV Build Monitor ===" -ForegroundColor Cyan
    $elapsed = (Get-Date) - $startTime
    Write-Host "Elapsed Time: $($elapsed.ToString('mm\:ss'))" -ForegroundColor Yellow
    Write-Host ""
    
    if (-not (Test-Path $buildDir)) {
        Write-Host "[Phase 1/5] Initializing..." -ForegroundColor Cyan
        Write-Host "  - Validating NDK and SDK" -ForegroundColor Gray
    }
    elseif (Test-Path "$buildDir\opencv.zip") {
        Write-Host "[Phase 2/5] Downloading OpenCV..." -ForegroundColor Cyan
        $size = (Get-Item "$buildDir\opencv.zip").Length / 1MB
        Write-Host "  - Downloaded: $([math]::Round($size, 2)) MB" -ForegroundColor Gray
    }
    elseif (-not (Test-Path "$buildDir\opencv-4.12.0")) {
        Write-Host "[Phase 3/5] Extracting source code..." -ForegroundColor Cyan
    }
    elseif (Test-Path "$buildDir\build_arm64-v8a\CMakeCache.txt" -and -not (Test-Path "$buildDir\build_arm64-v8a\install")) {
        Write-Host "[Phase 4/5] Building arm64-v8a..." -ForegroundColor Cyan
        $buildFiles = Get-ChildItem "$buildDir\build_arm64-v8a" -Recurse -File -ErrorAction SilentlyContinue
        Write-Host "  - Build files: $($buildFiles.Count)" -ForegroundColor Gray
        
        # Check for .o files to show compile progress
        $objFiles = Get-ChildItem "$buildDir\build_arm64-v8a" -Filter "*.o" -Recurse -ErrorAction SilentlyContinue
        if ($objFiles) {
            Write-Host "  - Object files compiled: $($objFiles.Count)" -ForegroundColor Gray
        }
    }
    elseif (Test-Path "$buildDir\build_armeabi-v7a\CMakeCache.txt") {
        Write-Host "[Phase 5/5] Building armeabi-v7a..." -ForegroundColor Cyan
        $buildFiles = Get-ChildItem "$buildDir\build_armeabi-v7a" -Recurse -File -ErrorAction SilentlyContinue
        Write-Host "  - Build files: $($buildFiles.Count)" -ForegroundColor Gray
    }
    elseif (Test-Path "app\src\main\jniLibs\arm64-v8a") {
        Write-Host "[Complete] Build finished!" -ForegroundColor Green
        Write-Host ""
        
        # Show built libraries
        $libs = Get-ChildItem "app\src\main\jniLibs" -Filter "*.so" -Recurse
        Write-Host "Built libraries:" -ForegroundColor Yellow
        foreach ($lib in $libs) {
            $sizeMB = $lib.Length / 1MB
            Write-Host "  $($lib.FullName.Replace((Get-Location).Path + '\', '')) - $([math]::Round($sizeMB, 2)) MB" -ForegroundColor Gray
        }
        
        Write-Host "`nNext step: Run .\verify_opencv_alignment.ps1" -ForegroundColor Cyan
        break
    }
    
    Write-Host "`nPress Ctrl+C to stop monitoring" -ForegroundColor DarkGray
    Start-Sleep -Seconds 3
}
