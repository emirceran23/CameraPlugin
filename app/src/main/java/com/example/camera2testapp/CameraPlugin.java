package com.example.camera2testapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;

public class CameraPlugin {

    private static final String TAG = "CameraPlugin";
    private static Activity unityActivity;
    public static CameraCallback callback;

    public static void initialize(Activity activity, CameraCallback cb) {
        unityActivity = activity;
        callback = cb;
        Log.d(TAG, "CameraPlugin initialized with callback");
    }

    public static void startCameraActivity() {
        if (unityActivity == null || callback == null) {
            Log.e(TAG, "CameraPlugin not initialized correctly");
            return;
        }
        Intent intent = new Intent(unityActivity, MainActivity.class);
        unityActivity.startActivity(intent);
    }

    public static void startCalibrationActivity() {
        if (unityActivity == null || callback == null) {
            Log.e(TAG, "CameraPlugin not initialized correctly");
            return;
        }
        Intent intent = new Intent(unityActivity, CalibrationActivity.class);
        unityActivity.startActivity(intent);
    }

    public static void finishCameraActivity() {
        if (unityActivity != null) {
            Intent intent = new Intent(unityActivity, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("finish", true);
            unityActivity.startActivity(intent);
        }
    }
    public static void sendResultToUnity(Bitmap bitmap) {
        if (callback == null) {
            Log.e(TAG, "Callback is null");
            return;
        }

        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
            byte[] imageBytes = stream.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            int orientation = 0;
            try {
                ExifInterface exif = new ExifInterface(new ByteArrayInputStream(imageBytes));
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException e) {
                Log.e(TAG, "Failed to read EXIF: " + e.getMessage());
            }

            // Format: "orientation::base64data"
            String payload = orientation + "::" + base64Image;
            callback.onPhotoCaptured(payload);

        } catch (Exception e) {
            Log.e(TAG, "Failed to send image: " + e.getMessage());
        }
    }
    public static void sendHeadPoseToUnity(HashMap<String, Float> headPoseDict) {
        if (callback == null) {
            Log.e(TAG, "Callback is null");
            return;
        }
        callback.OnHeadPoseReceived(headPoseDict);
    }

}
