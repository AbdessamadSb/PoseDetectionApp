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
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MediaPipeModule extends ReactContextBaseJavaModule {
    private static final String TAG = "MediaPipeModule";
    private static final String MODEL_FILE = "pose_landmarker_full.task";
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

    private File copyAssetToFile(String assetName) throws Exception {
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
                File modelFile = copyAssetToFile(MODEL_FILE);

                BaseOptions baseOptions = BaseOptions.builder()
                        .setModelAssetPath(modelFile.getAbsolutePath())
                        
                        .build(); // 🚀 No need to set delegate manually

                PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.VIDEO)
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

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        WritableArray allFramesResults = Arguments.createArray();

        try {
            Uri uri = Uri.parse(videoUri);
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            if (videoUri.startsWith("content://")) {
                retriever.setDataSource(reactContext, uri);
            } else {
                retriever.setDataSource(uri.getPath());
            }

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = Long.parseLong(durationStr);
            long frameInterval = 66; // ~15 FPS

            for (long time = 0; time < duration; time += frameInterval) {
                final long timestamp = time;
                executor.execute(() -> {
                    try {
                        Bitmap frame = retriever.getFrameAtTime(timestamp * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                        if (frame != null) {
                            int scaledWidth = 256;
                            int scaledHeight = (int) (frame.getHeight() * (scaledWidth / (float) frame.getWidth()));
                            Bitmap scaledFrame = Bitmap.createScaledBitmap(frame, scaledWidth, scaledHeight, true);

                            MPImage mpImage = new BitmapImageBuilder(scaledFrame).build();
                            PoseLandmarkerResult result = poseLandmarker.detectForVideo(mpImage, timestamp);

                            if (result != null && !result.landmarks().isEmpty()) {
                                WritableMap frameResult = createFrameResult(frame, result, timestamp);
                                synchronized (allFramesResults) {
                                    allFramesResults.pushMap(frameResult);
                                }
                            }
                            scaledFrame.recycle();
                            frame.recycle();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing frame at timestamp: " + timestamp, e);
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
            retriever.release();

            promise.resolve(allFramesResults);
        } catch (Exception e) {
            Log.e(TAG, "Error processing video", e);
            promise.reject("PROCESS_ERROR", "Failed to process video: " + e.getMessage(), e);
        }
    }

    private WritableMap createFrameResult(Bitmap frame, PoseLandmarkerResult result, double timestamp) {
        WritableMap frameResult = Arguments.createMap();
        frameResult.putDouble("timestamp", timestamp);

        String frameBase64 = bitmapToBase64(frame);
        frameResult.putString("frameImage", "data:image/jpeg;base64," + frameBase64);

        WritableArray landmarks = Arguments.createArray();
        List<NormalizedLandmark> poseLandmarks = result.landmarks().get(0);

        for (int i = 0; i < poseLandmarks.size(); i++) {
            NormalizedLandmark landmark = poseLandmarks.get(i);
            WritableMap landmarkMap = Arguments.createMap();
            landmarkMap.putString("name", getLandmarkName(i));
            landmarkMap.putDouble("x", landmark.x());
            landmarkMap.putDouble("y", landmark.y());
            landmarkMap.putDouble("z", landmark.z());
            landmarkMap.putDouble("visibility", landmark.visibility().orElse(1.0f));
            landmarkMap.putDouble("presence", landmark.presence().orElse(1.0f));
            landmarks.pushMap(landmarkMap);
        }

        frameResult.putArray("landmarks", landmarks);
        return frameResult;
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 480, 270, true);
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream);
        resizedBitmap.recycle();
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);
    }

    private String getLandmarkName(int index) {
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
