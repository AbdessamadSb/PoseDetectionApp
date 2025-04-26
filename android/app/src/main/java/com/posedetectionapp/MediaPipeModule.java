package com.posedetectionapp;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import com.google.mediapipe.framework.MediaPipeException;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class MediaPipeModule extends ReactContextBaseJavaModule {
    private static final String TAG = "MediaPipeModule";
    private static final String MODEL_FILE = "pose_landmarker_lite.task";
    private final ReactApplicationContext reactContext;
    private PoseLandmarker poseLandmarker;
    private boolean isInitialized = false;

    public MediaPipeModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "MediaPipeModule";
    }

    private File copyAssetToFile(String assetName) throws IOException {
        File outFile = new File(reactContext.getFilesDir(), assetName);
        if (!outFile.exists()) {
            try (InputStream in = reactContext.getAssets().open(assetName);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        }
        return outFile;
    }

    @ReactMethod
    public void initialize(Promise promise) {
        try {
            if (!isInitialized) {
                // Copy model file from assets to internal storage
                File modelFile = copyAssetToFile(MODEL_FILE);
                
                // Setup PoseLandmarker options
                BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelFile.getAbsolutePath())
                    .build();

                PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setNumPoses(1)
                    .build();

                poseLandmarker = PoseLandmarker.createFromOptions(reactContext, options);
                isInitialized = true;
            }
            promise.resolve("MediaPipe initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaPipe", e);
            promise.reject("INIT_ERROR", "Failed to initialize MediaPipe: " + e.getMessage(), e);
        }
    }

    @ReactMethod
    public void processVideo(String videoUri, Promise promise) {
        if (!isInitialized) {
            promise.reject("NOT_INITIALIZED", "MediaPipe not initialized");
            return;
        }

        try {
            // Parse the URI
            Uri uri = Uri.parse(videoUri);
            
            // Create a media retriever
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            if (videoUri.startsWith("content://")) {
                retriever.setDataSource(reactContext, uri);
            } else {
                retriever.setDataSource(uri.getPath());
            }
            
            // Get video duration
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = Long.parseLong(durationStr) * 1000; // Convert to microseconds
            
            // Process frames at intervals (10 FPS for smoother playback)
            long frameInterval = 100000; // 100ms = 10 FPS
            WritableArray allFramesResults = Arguments.createArray();
            
            // Process all frames for the entire video
            for (long time = 0; time < duration; time += frameInterval) {
                Bitmap frame = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST);
                
                if (frame != null) {
                    // Process frame with MediaPipe
                    WritableMap frameResult = processFrame(frame, time / 1000.0); // Convert back to ms
                    
                    if (frameResult != null) {
                        allFramesResults.pushMap(frameResult);
                    }
                    
                    frame.recycle();
                }
            }
            
            retriever.release();
            promise.resolve(allFramesResults);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing video", e);
            promise.reject("PROCESS_ERROR", "Failed to process video: " + e.getMessage(), e);
        }
    }
    
    private WritableMap processFrame(Bitmap frame, double timestamp) {
        try {
            // Convert Bitmap to MPImage
            MPImage mpImage = new BitmapImageBuilder(frame).build();
            
            // Process the image
            PoseLandmarkerResult result = poseLandmarker.detect(mpImage);
            
            if (result == null || result.landmarks().isEmpty()) {
                return null;
            }
            
            WritableMap frameResult = Arguments.createMap();
            frameResult.putDouble("timestamp", timestamp);
            
            // Convert frame to base64
            String frameBase64 = bitmapToBase64(frame);
            frameResult.putString("frameImage", "data:image/jpeg;base64," + frameBase64);
            
            // Extract landmarks
            WritableArray landmarks = Arguments.createArray();
            List<NormalizedLandmark> poseLandmarks = result.landmarks().get(0);
            
            for (int i = 0; i < poseLandmarks.size(); i++) {
                NormalizedLandmark landmark = poseLandmarks.get(i);
                WritableMap landmarkMap = Arguments.createMap();
                
                landmarkMap.putString("name", getLandmarkName(i));
                landmarkMap.putDouble("x", landmark.x());
                landmarkMap.putDouble("y", landmark.y());
                landmarkMap.putDouble("z", landmark.z());
                
                // Calculate visibility (using default value since presence is optional)
                landmarkMap.putDouble("visibility", 1.0);
                
                landmarks.pushMap(landmarkMap);
            }
            
            frameResult.putArray("landmarks", landmarks);
            return frameResult;
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
            return null;
        }
    }
    
    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // Compress to reduce size while maintaining quality for video playback
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, 640, 360, true);
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream);
        byte[] byteArray = outputStream.toByteArray();
        scaledBitmap.recycle(); // Free memory immediately
        return Base64.encodeToString(byteArray, Base64.NO_WRAP);
    }
    
    private String getLandmarkName(int index) {
        // Map MediaPipe landmark indices to names
        switch (index) {
            case 0: return "NOSE";
            case 1: return "LEFT_EYE_INNER";
            case 2: return "LEFT_EYE";
            case 3: return "LEFT_EYE_OUTER";
            case 4: return "RIGHT_EYE_INNER";
            case 5: return "RIGHT_EYE";
            case 6: return "RIGHT_EYE_OUTER";
            case 7: return "LEFT_EAR";
            case 8: return "RIGHT_EAR";
            case 9: return "MOUTH_LEFT";
            case 10: return "MOUTH_RIGHT";
            case 11: return "LEFT_SHOULDER";
            case 12: return "RIGHT_SHOULDER";
            case 13: return "LEFT_ELBOW";
            case 14: return "RIGHT_ELBOW";
            case 15: return "LEFT_WRIST";
            case 16: return "RIGHT_WRIST";
            case 17: return "LEFT_PINKY";
            case 18: return "RIGHT_PINKY";
            case 19: return "LEFT_INDEX";
            case 20: return "RIGHT_INDEX";
            case 21: return "LEFT_THUMB";
            case 22: return "RIGHT_THUMB";
            case 23: return "LEFT_HIP";
            case 24: return "RIGHT_HIP";
            case 25: return "LEFT_KNEE";
            case 26: return "RIGHT_KNEE";
            case 27: return "LEFT_ANKLE";
            case 28: return "RIGHT_ANKLE";
            case 29: return "LEFT_HEEL";
            case 30: return "RIGHT_HEEL";
            case 31: return "LEFT_FOOT_INDEX";
            case 32: return "RIGHT_FOOT_INDEX";
            default: return "UNKNOWN_" + index;
        }
    }
}