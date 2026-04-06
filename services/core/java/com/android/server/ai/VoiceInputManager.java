package com.android.server.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.android.server.UiThread;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音输入管理器 (VoiceInputManager)
 *
 * <p>管理音量键触发的语音输入流程：
 * <ol>
 *   <li>监听音量下键长按事件</li>
 *   <li>长按时开始录音</li>
 *   <li>松开时结束录音，发送 ASR 识别</li>
 *   <li>返回识别结果到 AgentService</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>
 * VoiceInputManager manager = new VoiceInputManager(context);
 * manager.setCallback(new VoiceInputManager.Callback() {
 *     {@literal @}Override
 *     public void onVoiceInputStart() {
 *         Log.d(TAG, "开始录音...");
 *     }
 *
 *     {@literal @}Override
 *     public void onVoiceInputEnd() {
 *         Log.d(TAG, "录音结束，等待识别...");
 *     }
 *
 *     {@literal @}Override
 *     public void onTranscript(String text) {
 *         Log.d(TAG, "识别结果: " + text);
 *         // 发送给 AgentService
 *     }
 *
 *     {@literal @}Override
 *     public void onError(String error) {
 *         Log.e(TAG, "错误: " + error);
 *     }
 * });
 *
 * manager.start();
 * </pre>
 *
 * @author Android System
 */
public class VoiceInputManager {
    private static final String TAG = "VoiceInputManager";

    /** 静态实例，供外部访问 */
    private static VoiceInputManager sInstance;

    /** 日志开关 */
    private static final boolean DEBUG = true;

    /** 长按阈值 500ms */
    private static final long LONG_PRESS_THRESHOLD_MS = 500;

    /** ASR 配置 */
    private static final int SAMPLE_RATE = 16000;
    private static final int PACKET_DURATION_MS = 200;
    private static final int PACKET_SIZE = SAMPLE_RATE * PACKET_DURATION_MS / 1000 * 2;

    /** 上下文 */
    private final Context mContext;
    /** 主线程 Handler */
    private final Handler mHandler;

    /**
     * 获取静态实例
     *
     * @return VoiceInputManager 实例，可能为 null
     */
    public static VoiceInputManager getInstance() {
        return sInstance;
    }
    /** 异步执行器 */
    private final Executor mExecutor;

    /** 音频录制管理器 */
    private final AudioRecordManager mAudioRecord;

    /** 回调接口 */
    private Callback mCallback;

    /** 状态标志 */
    private final AtomicBoolean mIsEnabled = new AtomicBoolean(false);
    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);
    private final AtomicBoolean mIsProcessing = new AtomicBoolean(false);
    private final AtomicBoolean mTriggered = new AtomicBoolean(false);

    /** 音量键触发状态 */
    private long mDownTime = 0;
    private boolean mTracking = false;

    /** 录制的音频数据 */
    private ByteBuffer mAudioBuffer;
    private int mAudioDataSize = 0;

    /** ASR 客户端 */
    private MiniMaxStreamingASRClient mAsrClient;

    /** 凭证 */
    private String mAppKey;
    private String mAccessKey;
    private String mResourceId = "volc.seedasr.sauc.duration";

    /** 消息类型 */
    private static final int MSG_CHECK_LONG_PRESS = 1;

    /**
     * 回调接口
     */
    public interface Callback {
        /** 开始录音 */
        default void onVoiceInputStart() {}

        /** 录音结束 */
        default void onVoiceInputEnd() {}

        /** 收到识别结果
         * @param text 识别文本
         */
        void onTranscript(String text);

        /** 发生错误
         * @param error 错误信息
         */
        void onError(String error);
    }

    /**
     * 构造函数
     */
    public VoiceInputManager(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = command -> UiThread.getHandler().post(command);

        mAudioRecord = new AudioRecordManager(context);

        // 保存静态实例
        sInstance = this;

        // 分配音频缓冲区 (最大 30 秒)
        mAudioBuffer = ByteBuffer.allocateDirect(SAMPLE_RATE * 30 * 2);
        mAudioBuffer.order(ByteOrder.LITTLE_ENDIAN);

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "VoiceInputManager 初始化");
            Log.i(TAG, "  - 采样率: " + SAMPLE_RATE + " Hz");
            Log.i(TAG, "  - 分包大小: " + PACKET_SIZE + " bytes");
            Log.i(TAG, "  - 长按阈值: " + LONG_PRESS_THRESHOLD_MS + " ms");
            Log.i(TAG, "========================================");
        }
    }

    /**
     * 设置 ASR 凭证
     */
    public void setCredentials(String appKey, String accessKey) {
        mAppKey = appKey;
        mAccessKey = accessKey;
        if (DEBUG) {
            Log.i(TAG, "ASR 凭证已设置");
        }
    }

    /**
     * 设置资源 ID
     */
    public void setResourceId(String resourceId) {
        mResourceId = resourceId;
    }

    /**
     * 设置回调
     */
    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    /**
     * 启动语音输入管理
     */
    public void start() {
        if (mIsEnabled.compareAndSet(false, true)) {
            if (DEBUG) Log.i(TAG, "VoiceInputManager 已启动");
        }
    }

    /**
     * 停止语音输入管理
     */
    public void stop() {
        if (mIsEnabled.compareAndSet(true, false)) {
            if (DEBUG) Log.i(TAG, "VoiceInputManager 已停止");
            // 停止录音
            if (mIsRecording.get()) {
                cancelRecording();
            }
        }
    }

    /**
     * 直接开始录音（由外部触发，如音量键长按）
     *
     * <p>这个方法会立即开始录音，不等待任何阈值。
     */
    public void startRecordingDirect() {
        if (DEBUG) {
            Log.i(TAG, "直接开始录音...");
        }
        startRecording();
    }

    /**
     * 直接停止录音并开始识别（由外部触发，如音量键松开）
     *
     * <p>这个方法会立即停止录音并开始 ASR 识别。
     */
    public void stopRecordingAndRecognizeDirect() {
        if (DEBUG) {
            Log.i(TAG, "直接停止录音并识别...");
        }
        stopRecordingAndRecognize();
    }

    /**
     * 处理按键事件
     *
     * <p>应该在 PhoneWindowManager 或 InputManager 中调用此方法。
     *
     * @param downTime 按下时间戳
     * @param eventTime 事件时间戳
     * @param isDown true 表示按下，false 表示松开
     * @return true 表示事件被消费
     */
    public boolean onKeyEvent(long downTime, long eventTime, boolean isDown) {
        if (!mIsEnabled.get()) {
            return false;
        }

        if (isDown) {
            return handleKeyDown(downTime);
        } else {
            return handleKeyUp(eventTime);
        }
    }

    /**
     * 处理按键按下
     */
    private boolean handleKeyDown(long downTime) {
        if (mTracking) {
            if (DEBUG) Log.d(TAG, "已在追踪中，忽略重复按下");
            return false;
        }

        mDownTime = downTime;
        mTracking = true;

        if (DEBUG) {
            Log.d(TAG, "开始追踪音量下键, downTime=" + downTime);
        }

        // 发送延迟消息检查长按
        mHandler.sendEmptyMessageDelayed(MSG_CHECK_LONG_PRESS, LONG_PRESS_THRESHOLD_MS);

        return false;
    }

    /**
     * 处理按键松开
     */
    private boolean handleKeyUp(long eventTime) {
        if (!mTracking) {
            return false;
        }

        long pressDuration = eventTime - mDownTime;

        if (DEBUG) {
            Log.d(TAG, "音量下键松开, 按压时长=" + pressDuration + " ms");
        }

        // 取消长按检查
        mHandler.removeMessages(MSG_CHECK_LONG_PRESS);

        // 重置追踪状态
        mTracking = false;

        // 如果按压时长超过阈值，停止录音并识别
        if (pressDuration >= LONG_PRESS_THRESHOLD_MS) {
            if (mIsRecording.get()) {
                stopRecordingAndRecognize();
            }
            return true; // 消费事件
        } else {
            // 短按，取消录音
            if (mIsRecording.get()) {
                cancelRecording();
            }
            return false;
        }
    }

    /**
     * 处理长按阈值到达
     */
    private void handleLongPressThreshold() {
        if (!mTracking) {
            return;
        }

        long pressDuration = SystemClock.elapsedRealtime() - mDownTime;
        if (pressDuration >= LONG_PRESS_THRESHOLD_MS) {
            if (DEBUG) {
                Log.i(TAG, "####################################");
                Log.i(TAG, "# 音量下键长按 " + LONG_PRESS_THRESHOLD_MS + "ms 触发语音输入!");
                Log.i(TAG, "####################################");
            }
            startRecording();
        }
    }

    /**
     * 开始录音
     */
    private void startRecording() {
        if (!mIsRecording.compareAndSet(false, true)) {
            if (DEBUG) Log.w(TAG, "已经在录音中");
            return;
        }

        if (mTriggered.compareAndSet(false, true)) {
            if (DEBUG) Log.i(TAG, "开始录音...");

            // 重置音频缓冲区
            mAudioBuffer.clear();
            mAudioDataSize = 0;

            // 设置录音回调
            mAudioRecord.setCallback(new AudioRecordManager.Callback() {
                @Override
                public void onAudioData(byte[] data, int size) {
                    // 累积音频数据
                    appendAudioData(data, size);
                }

                @Override
                public void onSpeechStart() {
                    if (DEBUG) Log.d(TAG, "检测到语音开始");
                }

                @Override
                public void onSpeechEnd(byte[] audioData) {
                    if (DEBUG) Log.d(TAG, "检测到语音结束 (VAD)");
                    // VAD 检测到语音结束，停止录音并识别
                    stopRecordingAndRecognize();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "录音错误: " + error);
                    mIsRecording.set(false);
                    mTriggered.set(false);
                    mCallback.onError("录音错误: " + error);
                }
            });

            // 开始录音
            boolean started = mAudioRecord.startListening();
            if (started) {
                mCallback.onVoiceInputStart();
            } else {
                mIsRecording.set(false);
                mTriggered.set(false);
                mCallback.onError("启动录音失败");
            }
        }
    }

    /**
     * 停止录音并开始识别
     */
    private void stopRecordingAndRecognize() {
        if (!mIsRecording.compareAndSet(true, false)) {
            return;
        }

        if (DEBUG) Log.i(TAG, "停止录音，开始识别...");

        // 停止 AudioRecord
        mAudioRecord.stopListening();

        mCallback.onVoiceInputEnd();

        // 获取录制的音频数据
        final byte[] audioData = getAudioData();
        if (audioData == null || audioData.length == 0) {
            mTriggered.set(false);
            mCallback.onError("音频数据为空");
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "音频数据: " + audioData.length + " bytes, "
                    + (audioData.length / 2 / SAMPLE_RATE) + " 秒");
        }

        // 开始 ASR 识别
        mExecutor.execute(() -> recognizeAudio(audioData));
    }

    /**
     * 取消录音
     */
    private void cancelRecording() {
        if (!mIsRecording.compareAndSet(true, false)) {
            return;
        }

        if (DEBUG) Log.i(TAG, "取消录音");

        mAudioRecord.stopListening();
        mTriggered.set(false);

        // 重置音频缓冲区
        mAudioBuffer.clear();
        mAudioDataSize = 0;
    }

    /**
     * 追加音频数据
     */
    private void appendAudioData(byte[] data, int size) {
        if (mAudioBuffer.remaining() < size) {
            // 缓冲区满，扩展
            if (DEBUG) Log.w(TAG, "音频缓冲区满，扩展缓冲区");
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(mAudioBuffer.capacity() * 2);
            newBuffer.order(ByteOrder.LITTLE_ENDIAN);
            mAudioBuffer.flip();
            newBuffer.put(mAudioBuffer);
            mAudioBuffer = newBuffer;
        }
        mAudioBuffer.put(data, 0, size);
        mAudioDataSize += size;
    }

    /**
     * 获取录制的音频数据
     */
    private byte[] getAudioData() {
        if (mAudioDataSize == 0) {
            return null;
        }
        byte[] data = new byte[mAudioDataSize];
        mAudioBuffer.flip();
        mAudioBuffer.get(data, 0, mAudioDataSize);
        mAudioBuffer.clear();
        mAudioDataSize = 0;
        return data;
    }

    /**
     * 开始 ASR 识别
     */
    private void recognizeAudio(byte[] audioData) {
        if (mIsProcessing.get()) {
            if (DEBUG) Log.w(TAG, "正在识别中，跳过");
            return;
        }

        if (mAppKey == null || mAppKey.isEmpty() ||
            mAccessKey == null || mAccessKey.isEmpty()) {
            mCallback.onError("ASR 凭证未设置");
            return;
        }

        if (!mIsEnabled.get()) {
            return;
        }

        if (!mIsProcessing.compareAndSet(false, true)) {
            return;
        }

        if (DEBUG) Log.i(TAG, "开始 ASR 识别...");

        // 确保在主线程创建 ASR 客户端（需要 Handler）
        mHandler.post(() -> {
            try {
                // 创建 ASR 客户端
                mAsrClient = new MiniMaxStreamingASRClient.Builder()
                        .setAppKey(mAppKey)
                        .setAccessKey(mAccessKey)
                        .setResourceId(mResourceId)
                        .build();

                mAsrClient.setCallback(new MiniMaxStreamingASRClient.Callback() {
                    @Override
                    public void onConnecting() {
                        if (DEBUG) Log.d(TAG, "ASR 正在连接...");
                    }

                    @Override
                    public void onConnected() {
                        if (DEBUG) Log.d(TAG, "ASR 已连接，开始发送音频...");
                        // 分包发送音频
                        sendAudioPackets(audioData);
                    }

                    @Override
                    public void onTranscript(String text, boolean isFinal) {
                        if (DEBUG) {
                            Log.d(TAG, "ASR 结果: " + text + " (isFinal=" + isFinal + ")");
                        }
                        if (isFinal && text != null && !text.isEmpty()) {
                            mCallback.onTranscript(text);
                            // 关闭连接
                            mAsrClient.close();
                            mIsProcessing.set(false);
                            mTriggered.set(false);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "ASR 错误: " + error);
                        mCallback.onError(error);
                        mIsProcessing.set(false);
                        mTriggered.set(false);
                    }

                    @Override
                    public void onClosed() {
                        if (DEBUG) Log.d(TAG, "ASR 连接已关闭");
                        mIsProcessing.set(false);
                    }
                });

                mAsrClient.connect();

            } catch (Exception e) {
                Log.e(TAG, "ASR 识别失败", e);
                mCallback.onError("ASR 识别失败: " + e.getMessage());
                mIsProcessing.set(false);
                mTriggered.set(false);
            }
        });
    }

    /**
     * 分包发送音频数据
     */
    private void sendAudioPackets(byte[] audioData) {
        if (mAsrClient == null || !mAsrClient.isConnected()) {
            if (DEBUG) Log.w(TAG, "ASR 未连接，无法发送音频");
            return;
        }

        int offset = 0;
        int sequence = 0;

        if (DEBUG) Log.d(TAG, "开始分包发送音频: " + audioData.length + " bytes");

        while (offset < audioData.length) {
            int len = Math.min(PACKET_SIZE, audioData.length - offset);
            byte[] packet = new byte[len];
            System.arraycopy(audioData, offset, packet, 0, len);

            boolean isLast = (offset + len >= audioData.length);
            mAsrClient.sendAudio(packet, isLast);

            offset += len;
            sequence++;

            // 每包间隔 10ms (模拟实时输入)
            if (!isLast) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        if (DEBUG) Log.d(TAG, "音频发送完成, 共 " + sequence + " 包");
        // 注意：最后一包 isLast=true 会自动通知服务器结束，不需要额外发送 finish()
    }

    /**
     * 是否正在录音
     */
    public boolean isRecording() {
        return mIsRecording.get();
    }

    /**
     * 是否已启用
     */
    public boolean isEnabled() {
        return mIsEnabled.get();
    }

    /**
     * 释放资源
     */
    public void release() {
        stop();
        mAudioRecord.release();
        if (mAsrClient != null) {
            mAsrClient.close();
            mAsrClient = null;
        }
        if (DEBUG) Log.i(TAG, "VoiceInputManager 已释放");
    }
}
