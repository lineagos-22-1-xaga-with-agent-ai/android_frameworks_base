package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.SystemProperties;
import android.util.Slog;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.ScreenCapture;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;

import com.android.server.LocalServices;

/**
 * 屏幕截图管理器，负责捕获屏幕和图层内容。
 *
 * <p>此类提供了一系列方法来截取设备屏幕、捕获特定图层，并将截图转换为位图或PNG格式。
 * 主要用于AI代理服务中需要获取当前屏幕状态的场景。</p>
 *
 * @author AI Agent System
 */
public class AgentScreenCapture {
    private static final String TAG = "AgentScreenCapture";

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private final Point mScreenSize;

    /**
     * 构造方法，初始化屏幕截图管理器。
     *
     * <p>获取DisplayManager和DisplayManagerInternal服务并更新屏幕尺寸信息。</p>
     *
     * @param context 应用上下文，用于获取系统服务
     */
    public AgentScreenCapture(@NonNull Context context) {
        mContext = context;
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mScreenSize = new Point();
        updateScreenBounds();
        Slog.i(TAG, "AgentScreenCapture 初始化完成");
    }

    /**
     * 更新屏幕尺寸边界信息。
     *
     * <p>获取默认显示器的真实物理尺寸并存储到mScreenSize中。</p>
     */
    private void updateScreenBounds() {
        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (defaultDisplay != null) {
            defaultDisplay.getRealSize(mScreenSize);
            Slog.d(TAG, "屏幕尺寸已更新: " + mScreenSize.x + "x" + mScreenSize.y);
        } else {
            Slog.w(TAG, "无法获取默认显示器信息");
        }
    }

    /**
     * 截取当前屏幕的默认截图。
     *
     * <p>以1.0f的缩放比例截取整个屏幕内容。</p>
     *
     * @return 截图的Bitmap对象，截取失败返回null
     */
    @Nullable
    public Bitmap takeScreenshot() {
        return takeScreenshot(1.0f);
    }

    /**
     * 以指定缩放比例截取当前屏幕。
     *
     * <p>根据传入的scale参数调整截图分辨率，scale为1.0f时表示原始尺寸，
     * 小于1.0f时为缩小，大于1.0f时为放大。</p>
     *
     * @param scale 截图缩放比例，如0.5f表示缩小到50%，2.0f表示放大到200%
     * @return 截图的Bitmap对象，截取失败返回null
     */
    @Nullable
    public Bitmap takeScreenshot(float scale) {
        // 使用 DisplayManagerInternal.systemScreenshot 获取屏幕截图
        ScreenshotHardwareBuffer buffer = mDisplayManagerInternal.systemScreenshot(Display.DEFAULT_DISPLAY);
        if (buffer == null) {
            Slog.e(TAG, "DisplayManagerInternal.systemScreenshot 返回 null");
            return null;
        }

        Bitmap bitmap = buffer.asBitmap();
        if (bitmap == null) {
            Slog.e(TAG, "ScreenshotHardwareBuffer.asBitmap 返回 null");
            return null;
        }

        // 缩放处理
        if (scale != 1.0f) {
            int newWidth = (int) (bitmap.getWidth() * scale);
            int newHeight = (int) (bitmap.getHeight() * scale);
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
            bitmap.recycle();
            bitmap = scaledBitmap;
        }

        Slog.i(TAG, "屏幕截图成功，尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        return bitmap;
    }

    /**
     * 捕获指定图层的截图。
     *
     * <p>使用默认参数（无裁剪、1.0f缩放）捕获单个SurfaceControl图层的内容。</p>
     *
     * @param layer 要捕获的SurfaceControl图层对象
     * @return 图层内容的Bitmap对象，捕获失败返回null
     */
    @Nullable
    public Bitmap captureLayer(@NonNull SurfaceControl layer) {
        return captureLayer(layer, null, 1.0f);
    }

    /**
     * 以指定参数捕获图层的截图。
     *
     * <p>支持自定义裁剪区域和缩放比例来捕获特定的图层内容。</p>
     *
     * @param layer 要捕获的SurfaceControl图层对象
     * @param sourceCrop 源裁剪区域，null表示捕获整个图层
     * @param scale 缩放比例，1.0f表示原始尺寸
     * @return 图层内容的Bitmap对象，捕获失败返回null
     */
    @Nullable
    public Bitmap captureLayer(@NonNull SurfaceControl layer, @Nullable Rect sourceCrop,
            float scale) {
        Slog.d(TAG, "开始捕获图层，缩放比例: " + scale + "，裁剪区域: " + sourceCrop);

        ScreenCapture.LayerCaptureArgs captureArgs =
                new ScreenCapture.LayerCaptureArgs.Builder(layer)
                        .setSourceCrop(sourceCrop)
                        .setFrameScale(scale)
                        .build();

        ScreenshotHardwareBuffer buffer;
        try {
            buffer = ScreenCapture.captureLayers(captureArgs);
        } catch (Exception e) {
            Slog.e(TAG, "调用ScreenCapture.captureLayers失败", e);
            return null;
        }

        if (buffer == null) {
            Slog.w(TAG, "图层捕获返回的缓冲区为空");
            return null;
        }

        Bitmap bitmap = buffer.asBitmap();
        Slog.i(TAG, "图层捕获成功，尺寸: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));
        return bitmap;
    }

    /**
     * 截取屏幕并以PNG格式返回字节数据。
     *
     * <p>这是takeScreenshot的便捷封装方法，将Bitmap转换为PNG格式的字节数组，
     * 适用于需要传输或存储截图数据的场景。</p>
     *
     * @return PNG格式的字节数组，截取失败返回null
     */
    @Nullable
    public byte[] takeScreenshotAsPng() {
        Bitmap bitmap = takeScreenshot();
        if (bitmap == null) {
            Slog.w(TAG, "PNG转换失败：原始截图为空");
            return null;
        }

        Slog.d(TAG, "开始将截图转换为PNG格式");

        java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        bitmap.recycle();

        byte[] pngData = stream.toByteArray();
        Slog.i(TAG, "PNG转换成功，数据大小: " + pngData.length + " 字节");
        return pngData;
    }

    /**
     * 获取屏幕宽度。
     *
     * @return 屏幕宽度（像素）
     */
    public int getScreenWidth() {
        return mScreenSize.x;
    }

    /**
     * 获取屏幕高度。
     *
     * @return 屏幕高度（像素）
     */
    public int getScreenHeight() {
        return mScreenSize.y;
    }
}