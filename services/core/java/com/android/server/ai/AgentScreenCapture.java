package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.ScreenCapture;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;

public class AgentScreenCapture {
    private static final String TAG = "AgentScreenCapture";

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final Rect mScreenBounds;

    public AgentScreenCapture(@NonNull Context context) {
        mContext = context;
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mScreenBounds = new Rect();
        updateScreenBounds();
    }

    private void updateScreenBounds() {
        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (defaultDisplay != null) {
            defaultDisplay.getRealSize(mScreenBounds);
        }
    }

    @Nullable
    public Bitmap takeScreenshot() {
        return takeScreenshot(1.0f);
    }

    @Nullable
    public Bitmap takeScreenshot(float scale) {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            Log.e(TAG, "Default display not found");
            return null;
        }

        IBinder displayToken = display.getDisplayToken();
        if (displayToken == null) {
            Log.e(TAG, "Display token is null");
            return null;
        }

        ScreenCapture.DisplayCaptureArgs.Builder captureArgsBuilder =
                new ScreenCapture.DisplayCaptureArgs.Builder(displayToken);

        if (scale != 1.0f) {
            int width = (int) (mScreenBounds.width() * scale);
            int height = (int) (mScreenBounds.height() * scale);
            captureArgsBuilder.setWidth(width);
            captureArgsBuilder.setHeight(height);
        }

        ScreenCapture.DisplayCaptureArgs captureArgs = captureArgsBuilder.build();

        ScreenshotHardwareBuffer buffer;
        try {
            buffer = ScreenCapture.captureDisplay(captureArgs);
        } catch (Exception e) {
            Log.e(TAG, "Failed to capture screen", e);
            return null;
        }

        if (buffer == null) {
            Log.e(TAG, "Screenshot buffer is null");
            return null;
        }

        return buffer.asBitmap();
    }

    @Nullable
    public Bitmap captureLayer(@NonNull SurfaceControl layer) {
        return captureLayer(layer, null, 1.0f);
    }

    @Nullable
    public Bitmap captureLayer(@NonNull SurfaceControl layer, @Nullable Rect sourceCrop,
            float scale) {
        ScreenCapture.LayerCaptureArgs captureArgs =
                new ScreenCapture.LayerCaptureArgs.Builder(layer)
                        .setSourceCrop(sourceCrop)
                        .setFrameScale(scale)
                        .build();

        ScreenshotHardwareBuffer buffer;
        try {
            buffer = ScreenCapture.captureLayers(captureArgs);
        } catch (Exception e) {
            Log.e(TAG, "Failed to capture layer", e);
            return null;
        }

        if (buffer == null) {
            return null;
        }

        return buffer.asBitmap();
    }

    @Nullable
    public byte[] takeScreenshotAsPng() {
        Bitmap bitmap = takeScreenshot();
        if (bitmap == null) {
            return null;
        }

        java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        bitmap.recycle();
        return stream.toByteArray();
    }

    public int getScreenWidth() {
        return mScreenBounds.width();
    }

    public int getScreenHeight() {
        return mScreenBounds.height();
    }
}
