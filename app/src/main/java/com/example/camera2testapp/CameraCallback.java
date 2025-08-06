package com.example.camera2testapp;

import java.util.HashMap;

public interface CameraCallback {
    void onPhotoCaptured(String base64Image);
    void OnCalibrationFinished(boolean isCalibrated);
    void OnHeadPoseReceived(HashMap<String, Float> headPoseDict);
}
