package com.android.server.ai;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import com.android.server.UiThread;

/**
 * 音频录制管理器 (AudioRecordManager)
 *
 * <p>负责管理设备麦克风的音频录制，支持：
 * <ul>
 *   <li>持续监听麦克风输入</li>
 *   <li>语音活动检测 (VAD)</li>
 *   <li>音频数据回调</li>
 *   <li>低功耗模式</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>
 * AudioRecordManager manager = new AudioRecordManager(context);
 * manager.setCallback(new AudioRecordManager.Callback() {
 *     {@literal @}Override
 *     public void onAudioData(byte[] data, int size) {
 *         // 处理音频数据
 *     }
 *
 *     {@literal @}Override
 *     public void onSpeechStart() {
 *         // 检测到语音开始
 *     }
 *
 *     {@literal @}Override
 *     public void onSpeechEnd(byte[] audioData) {
 *         // 语音结束，返回完整音频数据
 *     }
 * });
 *
 * manager.startListening();
 * </pre>
 *
 * @author Android System
 */
public class AudioRecordManager {
    private static final String TAG = "AudioRecordManager";

    /** 采样率 16kHz - 适合语音识别 */
    private static final int SAMPLE_RATE = 16000;
    /** 单声道 */
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    /** 16bit PCM */
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /** 缓冲区大小 - 10秒语音缓冲 */
    private static final int BUFFER_SIZE_FACTOR = 100;
    /** 最小缓冲区大小 */
    private static final int MIN_BUFFER_SIZE;

    /** 语音检测阈值 dB */
    private static final double VOICE_THRESHOLD_DB = -40.0;
    /** 语音开始静音时长阈值 ms */
    private static final int SILENCE_THRESHOLD_MS = 300;
    /** 语音结束静音时长阈值 ms */
    private static final int SPEECH_END_SILENCE_MS = 800;
    /** 每次读取的时长 ms */
    private static final int READ_INTERVAL_MS = 50;

    static {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        MIN_BUFFER_SIZE = Math.max(minBufferSize, SAMPLE_RATE * 2); // 至少 1 秒
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Executor mExecutor;

    /** AudioRecord 实例 */
    private AudioRecord mAudioRecord;
    /** 是否正在录音 */
    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);
    /** 是否暂停监听 */
    private final AtomicBoolean mIsPaused = new AtomicBoolean(false);

    /** 录音线程 */
    private Thread mRecordThread;

    /** 音频数据回调 */
    private Callback mCallback;

    /** 语音检测状态 */
    private boolean mIsSpeaking = false;
    /** 最后一个有效音频时间戳 */
    private long mLastVoiceTime = 0;
    /** 语音开始时间戳 */
    private long mSpeechStartTime = 0;

    /** 累积的音频数据 (用于语音结束回调) */
    private final ByteArrayOutputStream mSpeechBuffer = new ByteArrayOutputStream();

    /** 检测到的语音数据是否需要处理 */
    private final AtomicBoolean mHasNewSpeech = new AtomicBoolean(false);

    /**
     * 音频录制回调接口
     */
    public interface Callback {
        /**
         * 收到音频数据（实时）
         *
         * @param data 音频数据 (16bit PCM)
         * @param size 数据大小
         */
        void onAudioData(byte[] data, int size);

        /**
         * 检测到语音开始
         */
        void onSpeechStart();

        /**
         * 语音结束
         *
         * @param audioData 完整语音数据 (16bit PCM)
         */
        void onSpeechEnd(byte[] audioData);

        /**
         * 录音错误
         *
         * @param error 错误信息
         */
        void onError(String error);
    }

    /**
     * 构造函数
     *
     * @param context 应用程序上下文
     */
    public AudioRecordManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = command -> {
            new Thread(command).start();
        };

        Log.i(TAG, "========================================");
        Log.i(TAG, "AudioRecordManager 初始化");
        Log.i(TAG, "  - 采样率: " + SAMPLE_RATE + " Hz");
        Log.i(TAG, "  - 通道: 单声道");
        Log.i(TAG, "  - 位深: 16bit PCM");
        Log.i(TAG, "  - 最小缓冲区: " + MIN_BUFFER_SIZE + " bytes");
        Log.i(TAG, "  - 语音阈值: " + VOICE_THRESHOLD_DB + " dB");
        Log.i(TAG, "========================================");
    }

    /**
     * 设置录音回调
     *
     * @param callback 回调接口，设置为 null 可清除
     */
    public void setCallback(Callback callback) {
        Log.d(TAG, "设置录音回调: " + (callback != null ? "已设置" : "已清除"));
        mCallback = callback;
    }

    /**
     * 开始录音监听
     *
     * <p>启动麦克风监听，持续检测语音输入。
     * 检测到语音时通过回调通知。
     *
     * @return true 表示启动成功
     */
    public boolean startListening() {
        if (mIsRecording.get()) {
            Log.w(TAG, "已在录音中，无需重复启动");
            return true;
        }

        if (mIsPaused.get()) {
            Log.w(TAG, "已暂停，先恢复");
            mIsPaused.set(false);
            return true;
        }

        Log.i(TAG, "========== 开始录音监听 ==========");

        try {
            // 创建 AudioRecord
            int bufferSize = MIN_BUFFER_SIZE * BUFFER_SIZE_FACTOR;
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                mAudioRecord.release();
                mAudioRecord = null;
                notifyError("AudioRecord 初始化失败");
                return false;
            }

            Log.i(TAG, "AudioRecord 初始化成功");
            Log.d(TAG, "  - 缓冲区大小: " + bufferSize + " bytes");

            // 开始录音
            mAudioRecord.startRecording();
            mIsRecording.set(true);
            mIsSpeaking = false;
            mLastVoiceTime = 0;
            mSpeechBuffer.reset();

            // 启动录音线程
            mRecordThread = new Thread(this::recordLoop, "AudioRecordThread");
            mRecordThread.start();

            Log.i(TAG, "录音监听已启动");
            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "录音权限不足", e);
            notifyError("录音权限不足: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "启动录音失败", e);
            notifyError("启动录音失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 停止录音监听
     */
    public void stopListening() {
        if (!mIsRecording.get()) {
            Log.w(TAG, "不在录音状态");
            return;
        }

        Log.i(TAG, "========== 停止录音监听 ==========");

        mIsRecording.set(false);
        mIsPaused.set(false);

        // 停止录音线程
        if (mRecordThread != null) {
            mRecordThread.interrupt();
            try {
                mRecordThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "等待录音线程结束被中断");
            }
            mRecordThread = null;
        }

        // 停止 AudioRecord
        if (mAudioRecord != null) {
            try {
                if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    mAudioRecord.stop();
                }
                mAudioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "停止 AudioRecord 失败", e);
            }
            mAudioRecord = null;
        }

        Log.i(TAG, "录音监听已停止");
    }

    /**
     * 暂停录音监听
     *
     * <p>暂停后不消耗资源，但保持状态。需要时调用 resumeListening() 恢复。
     */
    public void pauseListening() {
        if (!mIsRecording.get()) {
            Log.w(TAG, "不在录音状态，无法暂停");
            return;
        }

        Log.i(TAG, "暂停录音监听");
        mIsPaused.set(true);
    }

    /**
     * 恢复录音监听
     */
    public void resumeListening() {
        if (!mIsRecording.get()) {
            Log.w(TAG, "不在录音状态，无法恢复");
            return;
        }

        if (!mIsPaused.get()) {
            Log.w(TAG, "未暂停，无需恢复");
            return;
        }

        Log.i(TAG, "恢复录音监听");
        mIsPaused.set(false);
    }

    /**
     * 录音循环
     */
    private void recordLoop() {
        Log.d(TAG, "录音线程开始");

        byte[] buffer = new byte[MIN_BUFFER_SIZE];
        int bytesPerRead = (SAMPLE_RATE * READ_INTERVAL_MS) / 1000 * 2; // 16bit

        while (mIsRecording.get()) {
            if (mIsPaused.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            try {
                int bytesRead = mAudioRecord.read(buffer, 0, bytesPerRead);

                if (bytesRead > 0) {
                    // 处理音频数据
                    processAudioData(buffer, bytesRead);
                }

            } catch (Exception e) {
                if (mIsRecording.get()) {
                    Log.e(TAG, "读取音频数据失败", e);
                }
                break;
            }
        }

        Log.d(TAG, "录音线程结束");
    }

    /**
     * 处理音频数据
     *
     * @param buffer 音频数据
     * @param size 数据大小
     */
    private void processAudioData(byte[] buffer, int size) {
        // 计算音频能量
        double db = calculateAudioDb(buffer, size);

        // 回调音频数据
        if (mCallback != null) {
            byte[] data = new byte[size];
            System.arraycopy(buffer, 0, data, 0, size);
            mHandler.post(() -> mCallback.onAudioData(data, size));
        }

        // 语音检测
        if (db > VOICE_THRESHOLD_DB) {
            // 检测到有效语音
            handleVoiceStart();
        } else {
            // 静音
            handleSilence();
        }
    }

    /**
     * 计算音频分贝值
     *
     * @param buffer 音频数据
     * @param size 数据大小
     * @return 分贝值
     */
    private double calculateAudioDb(byte[] buffer, int size) {
        long sum = 0;
        int count = size / 2; // 16bit samples

        for (int i = 0; i < size - 1; i += 2) {
            // 转换为 short (little endian)
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            sum += sample * sample;
        }

        if (count == 0) return -100;

        double rms = Math.sqrt((double) sum / count);
        if (rms == 0) return -100;

        // 转换为 dB
        return 20 * Math.log10(rms / Short.MAX_VALUE);
    }

    /**
     * 处理语音开始
     */
    private void handleVoiceStart() {
        long now = SystemClock.elapsedRealtime();

        if (!mIsSpeaking) {
            // 语音开始
            Log.i(TAG, "检测到语音开始");
            mIsSpeaking = true;
            mSpeechStartTime = now;
            mSpeechBuffer.reset();

            // 通知语音开始
            if (mCallback != null) {
                mHandler.post(() -> mCallback.onSpeechStart());
            }
        }

        mLastVoiceTime = now;
    }

    /**
     * 处理静音
     */
    private void handleSilence() {
        long now = SystemClock.elapsedRealtime();

        if (mIsSpeaking) {
            long silenceDuration = now - mLastVoiceTime;

            if (silenceDuration > SPEECH_END_SILENCE_MS) {
                // 语音结束
                handleSpeechEnd();
            }
        }
    }

    /**
     * 处理语音结束
     */
    private synchronized void handleSpeechEnd() {
        if (!mIsSpeaking) return;

        mIsSpeaking = false;
        long duration = SystemClock.elapsedRealtime() - mSpeechStartTime;

        byte[] audioData = mSpeechBuffer.toByteArray();

        Log.i(TAG, "检测到语音结束");
        Log.i(TAG, "  - 语音时长: " + duration + " ms");
        Log.i(TAG, "  - 音频数据: " + audioData.length + " bytes");

        // 通知语音结束
        if (mCallback != null && audioData.length > 0) {
            mHandler.post(() -> mCallback.onSpeechEnd(audioData));
        }

        mSpeechBuffer.reset();
    }

    /**
     * 检查录音权限
     *
     * @return true 表示有权限
     */
    public boolean hasAudioPermission() {
        return mContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 请求录音权限
     *
     * <p>需要在 Activity 中处理权限回调
     */
    public void requestAudioPermission() {
        Log.i(TAG, "请求录音权限");
        // 注意：这个方法需要调用者在 Activity 中实际请求权限
        // 这里只是日志记录
    }

    /**
     * 是否正在录音
     */
    public boolean isRecording() {
        return mIsRecording.get();
    }

    /**
     * 是否已暂停
     */
    public boolean isPaused() {
        return mIsPaused.get();
    }

    /**
     * 获取当前采样率
     */
    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    /**
     * 通知错误
     */
    private void notifyError(String error) {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onError(error));
        }
    }

    /**
     * 获取诊断信息
     */
    public String getDiagnosticInfo() {
        return "AudioRecordManager Diagnostics:\n"
                + "  isRecording: " + mIsRecording.get() + "\n"
                + "  isPaused: " + mIsPaused.get() + "\n"
                + "  isSpeaking: " + mIsSpeaking + "\n"
                + "  lastVoiceTime: " + mLastVoiceTime + "\n"
                + "  sampleRate: " + SAMPLE_RATE + "\n"
                + "  hasPermission: " + hasAudioPermission() + "\n";
    }

    /**
     * 释放资源
     */
    public void release() {
        stopListening();
        mCallback = null;
        Log.i(TAG, "AudioRecordManager 已释放");
    }
}
