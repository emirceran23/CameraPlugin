package com.example.camera2testapp;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

public class CameraPlugin {
    private static Activity unityActivity;

    // Initialize with Unity activity
    public static void Init(Activity activity) {
        unityActivity = activity;
    }

    // Start the Camera activity
    public static void StartCamera() {
        if (unityActivity != null) {
            Intent intent = new Intent(unityActivity, MainActivity.class);
            unityActivity.startActivity(intent);
        } else {
            Log.e("CameraPlugin", "Unity Activity is null!");
        }
    }

    // Stop the Camera activity
    public static void StopCamera() {
        if (unityActivity != null) {
            unityActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    unityActivity.finish();  // Finish the camera activity
                }
            });
        } else {
            Log.e("CameraPlugin", "Unity Activity is null!");
        }
    }
    public void autoCapturePhoto() {
        if (unityActivity != null) {
            Intent intent = new Intent(unityActivity, MainActivity.class);
            intent.putExtra("AUTO_CAPTURE", true);
            unityActivity.startActivity(intent);
        } else {
            Log.e("CameraPlugin", "Unity Activity is null!");
        }


    }

}
