package com.example.camera2testapp;

// OpenCV imports
import org.opencv.android.OpenCVLoader;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point3;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.TermCriteria;
import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class CameraCalibration {
    private static final String TAG = "CameraCalibration";

    // Size of the checkerboard pattern (number of inner corners per chessboard row and column)
    private final Size patternSize;           // (columns, rows)
    // Physical size of a single square on the checkerboard (e.g. millimetres)
    private final double squareSize;

    // Accumulated object/ image points for calibration
    private final List<Mat> objectPoints = new ArrayList<>();
    private final List<Mat> imagePoints  = new ArrayList<>();

    // Calibration results
    private Mat cameraMatrix = new Mat();
    private Mat distCoeffs   = new Mat();
    private boolean calibrated = false;

    private Size imageSize = null;

    private MatOfPoint2f lastCorners = null; // stores corners detected in the most recent frame

    /**
     * Creates a new calibration helper.
     * @param cornerCols  number of inner corners along the width (columns)
     * @param cornerRows  number of inner corners along the height (rows)
     * @param squareSize  physical size of one square (same unit for all)
     */
    public CameraCalibration(int cornerCols, int cornerRows, double squareSize) {
        // Ensure OpenCV native libs are loaded.
        if (!OpenCVLoader.initDebug()) {
            throw new IllegalStateException("❌ OpenCV failed to load. Make sure the OpenCV dependency is correctly added.");
        }
        this.patternSize = new Size(cornerCols, cornerRows);
        this.squareSize  = squareSize;
    }

    // region --- Public API --------------------------------------------------------------------

    /**
     * Attempts to detect a checkerboard in the provided RGBA frame and, if successful, stores the
     * detected corners for later calibration.
     * @param rgbaFrame frame in RGBA colour space (as obtained from TextureView / ImageReader)
     * @return true if the pattern was found and stored; false otherwise
     */
    public boolean addFrame(Mat rgbaFrame) {
        Log.d(TAG, "addFrame called. Input mat size=" + rgbaFrame.width() + "x" + rgbaFrame.height());
        if (rgbaFrame == null || rgbaFrame.empty()) return false;

        // Convert to grayscale for corner detection
        Mat gray = new Mat();
        Imgproc.cvtColor(rgbaFrame, gray, Imgproc.COLOR_RGBA2GRAY);

        MatOfPoint2f corners = new MatOfPoint2f();
        boolean found = Calib3d.findChessboardCorners(
                gray,
                patternSize,
                corners,
                Calib3d.CALIB_CB_ADAPTIVE_THRESH +
                        Calib3d.CALIB_CB_NORMALIZE_IMAGE +
                        Calib3d.CALIB_CB_FAST_CHECK);

        if (found) {
            // resolution check deferred until after we know size
            // Ensure all frames have the same resolution. If this frame's size differs from the first
            // accepted frame, ignore it to prevent assertions inside calibrateCamera().
            if (imageSize != null && !imageSize.equals(gray.size())) {
                Log.w(TAG, "Skipping frame due to resolution mismatch. Expected=" + imageSize + " got=" + gray.size());
                gray.release();
                return false; // different resolution – skip
            }

            // Refine corner positions for higher accuracy
            Imgproc.cornerSubPix(
                    gray,
                    corners,
                    new Size(11, 11),
                    new Size(-1, -1),
                    new TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 30, 0.1));

            // Prepare corresponding 3D points, assuming Z = 0 plane
            Mat obj = createObjectCorners();
            MatOfPoint2f clonedCorners = new MatOfPoint2f();
            corners.copyTo(clonedCorners); // deep copy

            imagePoints.add(clonedCorners);
            objectPoints.add(obj);

            Log.d(TAG, "Checkerboard accepted. Total accepted frames=" + imagePoints.size());

            // Keep a reference to the last detected set of corners so that callers can visualise
            // them. We store the Mat directly so that the caller can decide how/when to convert
            // it. Note: A shallow copy is sufficient because the underlying data will not change
            // after this point.
            lastCorners = clonedCorners;

            if (imageSize == null) {
                imageSize = gray.size();
            }
        } else {
            // Pattern not found in this frame
            Log.d(TAG, "Checkerboard NOT found in this frame.");
            lastCorners = null;
        }
        corners.release();

        gray.release();
        return found;
    }

    /**
     * Convenience overload that accepts a Bitmap frame in ARGB_8888 format.
     * Internally converts it to an OpenCV Mat in RGBA colour space and delegates
     * to {@link #addFrame(Mat)}.
     */
    public boolean addFrame(Bitmap bitmapFrame) {
        if (bitmapFrame == null) return false;
        Mat rgba = new Mat();
        Utils.bitmapToMat(bitmapFrame, rgba);
        boolean result = addFrame(rgba);
        rgba.release();
        return result;
    }

    /**
     * Performs camera calibration using all previously accepted frames.
     * @return reprojection error (RMS) or -1 if calibration failed.
     */
    public double calibrate() {
        if (objectPoints.size() < 3 || imageSize == null) {
            return -1; // Not enough data
        }

        List<Mat> rvecs = new ArrayList<>();
        List<Mat> tvecs = new ArrayList<>();

        Log.d(TAG, "Starting calibrateCamera with " + objectPoints.size() + " frames, imageSize=" + imageSize);
        double rms = Calib3d.calibrateCamera(
                objectPoints,
                imagePoints,
                imageSize,
                cameraMatrix,
                distCoeffs,
                rvecs,
                tvecs,
                Calib3d.CALIB_RATIONAL_MODEL + Calib3d.CALIB_FIX_K4 + Calib3d.CALIB_FIX_K5);

        calibrated = rms > 0;
        Log.d(TAG, "calibrateCamera finished. RMS=" + rms + " calibrated=" + calibrated);
        for (Mat m : objectPoints) m.release();
        for (Mat m : imagePoints) m.release();
        objectPoints.clear();
        imagePoints.clear();

        return rms;
    }

    public boolean isCalibrated() {
        return calibrated;
    }

    public Mat getCameraMatrix() {
        return cameraMatrix;
    }

    public Mat getDistCoeffs() {
        return distCoeffs;
    }
    

    /**
     * Serialises calibration results to a JSON string.
     *
     * Desired output structure (example):
     * {
     *   "camera_matrix": {
     *     "fx": 1234.5,
     *     "fy": 1230.1,
     *     "cx": 640.0,
     *     "cy": 360.0
     *   },
     *   "distortion_coeffs": [k1, k2, p1, p2, k3, k4, k5, k6]
     * }
     */
    public String toJsonString() throws org.json.JSONException {
        if (!calibrated) {
            throw new IllegalStateException("Camera not calibrated yet");
        }

        JSONObject root = new JSONObject();

        // ------- Camera intrinsics (matrix with metadata) ----------
        JSONObject camObj = new JSONObject();
        camObj.put("rows", 3);
        camObj.put("cols", 3);

        JSONArray camData = new JSONArray();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                camData.put(cameraMatrix.get(r, c)[0]);
            }
        }
        camObj.put("data", camData);
        root.put("camera_matrix", camObj);

        // ------- Image resolution ----------------------
        if (imageSize != null) {
            root.put("calib_width", (int)imageSize.width);
            root.put("calib_height", (int)imageSize.height);
        }

        // ------- Distortion coefficients ---------------
        JSONArray distArr = new JSONArray();
        int cols = distCoeffs.cols() * distCoeffs.channels(); // channels usually 1
        int rows = distCoeffs.rows();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                distArr.put(distCoeffs.get(r, c)[0]);
            }
        }
        root.put("distortion_coeffs", distArr);

        return root.toString();
    }

    /**
     * Returns the corners detected in the last processed frame (in pixel coordinates of that
     * frame) or {@code null} if no checkerboard was detected.
     */
    public MatOfPoint2f getLastDetectedCorners() {
        return lastCorners;
    }

    // endregion --------------------------------------------------------------------------------

    // region --- Helpers -----------------------------------------------------------------------

    // Generates the 3D coordinates of the checkerboard corners assuming the checkerboard lies on
    // the Z=0 plane.
    private Mat createObjectCorners() {
        MatOfPoint3f objPts = new MatOfPoint3f();
        int numSquares = (int) (patternSize.width * patternSize.height);
        Point3[] points = new Point3[numSquares];
        int idx = 0;
        for (int i = 0; i < patternSize.height; i++) {
            for (int j = 0; j < patternSize.width; j++) {
                points[idx++] = new Point3(j * squareSize, i * squareSize, 0);
            }
        }
        objPts.fromArray(points);
        return objPts;
    }
    /** Removes the most-recently stored frame (used when we decide not to keep it). */
    public void discardLastFrame() {
        if (imagePoints.isEmpty()) return;
        int idx = imagePoints.size() - 1;

        imagePoints.get(idx).release();
        imagePoints.remove(idx);

        objectPoints.get(idx).release();
        objectPoints.remove(idx);

        Log.d(TAG, "Last frame discarded. Remaining = " + imagePoints.size());
    }


    // endregion --------------------------------------------------------------------------------
}
