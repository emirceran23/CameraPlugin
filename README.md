# Camera2 Test App

## Overview
This Android camera plugin utilizes the Camera2 API and MediaPipe for face and pose detection, head pose estimation, and distance calculation through iris detection. The plugin also incorporates calibration for accurate focal length determination using EXIF data, vibration alerts, and automatic flash control during photo capture.

## Features
- **Face and Pose Detection:** Utilizes MediaPipe's Face and Pose Landmarkers for accurate landmark detection.
- **Head Pose Estimation:** Computes head pose angles (pitch, yaw, roll) in real-time.
- **Distance Estimation:** Calculates approximate distance to camera using iris landmarks.
- **Auto-Capture:** Automatically captures photos when the face is properly aligned and at an optimal distance.
- **Calibration:** Automatic camera calibration for precise focal length measurement.
- **Flash and Vibration Feedback:** Provides visual (flash) and tactile (vibration) feedback upon capturing images.
- **Image Saving:** Automatically saves captured images to the device gallery.

## Dependencies
- Android Camera2 API
- MediaPipe FaceLandmarker and PoseLandmarker
- OpenCV (Android)
- AndroidX Libraries

## Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/camera2-test-app.git
   ```
2. Open in Android Studio.
3. Build and run on your device/emulator.

## Permissions
Ensure the following permission is included in your AndroidManifest.xml:
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

## Usage
- On startup, the app automatically requests camera permissions and performs an initial calibration.
- Ensure your face is well-lit and centered for accurate detection.
- Follow on-screen prompts for face orientation adjustments.
- The app will automatically capture images when conditions are optimal, providing vibration and flash feedback.

## Structure
- **MainActivity.kt:** Primary activity handling camera initialization, real-time detection, head pose calculation, and photo capturing.
- **PoseProcessor.kt:** Initializes and provides access to MediaPipe PoseLandmarker.

## Contributing
Contributions are welcome. Please open an issue or submit a pull request with your enhancements.

## License
This project is open-sourced under the [MIT License](LICENSE).

