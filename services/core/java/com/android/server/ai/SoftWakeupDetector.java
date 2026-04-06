package com.android.server.ai;

import android.content.Context;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 软件唤醒检测器 (SoftWakeupDetector)
 *
 * <p>基于麦克风 + 字节跳动 ASR 实现的软件级唤醒词检测。
 *
 * <p>工作流程：
 * <ol>
 *   <li>AudioRecordManager 持续监听麦克风</li>
 *   <li>检测到语音活动时，开始录音</li>
 *   <li>语音结束后，将音频发送到字节跳动流式 ASR API</li>
 *   <li>解析识别结果，检测是否包含唤醒词</li>
 *   <li>如果包含唤醒词，触发 onWakewordDetected 回调</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>
 * // 1. 创建检测器
 * SoftWakeupDetector detector = new SoftWakeupDetector(context);
 * detector.setCredentials("app-key", "access-key");
 *
 * // 2. 设置唤醒词
 * detector.setWakeword("你好助手");
 *
 * // 3. 设置回调
 * detector.setCallback(new SoftWakeupDetector.Callback() {
 *     {@literal @}Override
 *     public void onWakewordDetected() {
 *         Log.d(TAG, "唤醒词检测到！");
 *         // 开始接收语音命令
 *     }
 *
 *     {@literal @}Override
 *     public void onVoiceCommand(String text) {
 *         Log.d(TAG, "收到语音命令: " + text);
 *         // 处理语音命令
 *     }
 *
 *     {@literal @}Override
 *     public void onError(String error) {
 *         Log.e(TAG, "错误: " + error);
 *     }
 * });
 *
 * // 4. 启动检测
 * detector.start();
 * </pre>
 *
 * <p>注意：此实现需要 RECORD_AUDIO 权限
 *
 * @author Android System
 */
public class SoftWakeupDetector {
    private static final String TAG = "SoftWakeup";

    /** 日志开关 */
    private static final boolean DEBUG = true;

    /** 默认唤醒词 */
    private static final String DEFAULT_WAKEWORD = "你好助手";

    /** 上下文 */
    private final Context mContext;

    /** 音频录制管理器 */
    private final AudioRecordManager mAudioRecord;

    /** MiniMax ASR 客户端 (旧版，暂未使用) */
    private Object mAsrClient = null;

    /** ASR 配置凭证 */
    private String mAppKey;
    private String mAccessKey;
    private String mResourceId = "volc.seedasr.sauc.duration";

    /** 当前唤醒词 */
    private String mWakeword;

    /** 是否启用检测 */
    private final AtomicBoolean mIsEnabled = new AtomicBoolean(false);

    /** 是否正在监听 */
    private final AtomicBoolean mIsListening = new AtomicBoolean(false);

    /** 回调接口 */
    private Callback mCallback;

    /** 网络执行器 */
    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    /** 识别中的音频 */
    private final AtomicBoolean mIsRecognizing = new AtomicBoolean(false);

    /**
     * 回调接口
     */
    public interface Callback {
        /**
         * 唤醒词检测到
         *
         * <p>用户说出唤醒词后调用。
         * 之后应该开始接收语音命令。
         */
        void onWakewordDetected();

        /**
         * 收到语音命令
         *
         * <p>语音命令（唤醒词之后的内容）识别成功后调用。
         *
         * @param text 语音转写的文本
         */
        void onVoiceCommand(String text);

        /**
         * 发生错误
         *
         * @param error 错误信息
         */
        void onError(String error);
    }

    /**
     * 构造函数
     *
     * @param context 应用程序上下文
     * @param apiKey MiniMax API Key
     */
    public SoftWakeupDetector(Context context, String apiKey) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }

        mContext = context;
        mWakeword = DEFAULT_WAKEWORD;

        // 初始化音频录制管理器
        mAudioRecord = new AudioRecordManager(context);

        // TODO: ASR 客户端暂未使用，唤醒词功能暂时禁用
        // mAsrClient = new MiniMaxASRClient(apiKey);

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "SoftWakeupDetector 初始化");
            Log.i(TAG, "  - 唤醒词: " + mWakeword);
            Log.i(TAG, "  - API Key: " + (apiKey != null && !apiKey.isEmpty() ? "已设置" : "未设置"));
            Log.i(TAG, "========================================");
        }
    }

    /**
     * 设置 API Key
     *
     * @param apiKey MiniMax API Key
     */
    public void setApiKey(String apiKey) {
        // TODO: ASR 客户端暂未使用
        if (DEBUG) {
            Log.d(TAG, "API Key 更新 (暂未使用): " + (apiKey != null && !apiKey.isEmpty() ? "已设置" : "未设置"));
        }
    }

    /**
     * 设置唤醒词
     *
     * @param wakeword 唤醒词，如 "你好助手"
     */
    public void setWakeword(String wakeword) {
        if (wakeword == null || wakeword.trim().isEmpty()) {
            Log.w(TAG, "唤醒词为空，使用默认值");
            wakeword = DEFAULT_WAKEWORD;
        }
        mWakeword = wakeword.trim();
        if (DEBUG) {
            Log.i(TAG, "唤醒词已设置为: " + mWakeword);
        }
    }

    /**
     * 获取当前唤醒词
     */
    public String getWakeword() {
        return mWakeword;
    }

    /**
     * 设置回调
     *
     * @param callback 回调接口
     */
    public void setCallback(Callback callback) {
        this.mCallback = callback;
        if (DEBUG) {
            Log.d(TAG, "回调已设置: " + (callback != null ? "是" : "否"));
        }
    }

    /**
     * 启动软唤醒检测
     *
     * <p>开始监听麦克风，检测唤醒词。
     *
     * @return true 表示启动成功
     */
    public boolean start() {
        if (mIsEnabled.get()) {
            Log.w(TAG, "软唤醒检测已在运行");
            return true;
        }

        if (mAudioRecord == null) {
            notifyError("AudioRecordManager 未初始化");
            return false;
        }

        if (!mAudioRecord.hasAudioPermission()) {
            Log.w(TAG, "缺少录音权限");
            notifyError("缺少录音权限 RECORD_AUDIO");
            return false;
        }

        if (DEBUG) {
            Log.i(TAG, "========== 启动软唤醒检测 ==========");
            Log.d(TAG, "  - 唤醒词: " + mWakeword);
            Log.d(TAG, "  - 采样率: " + mAudioRecord.getSampleRate() + " Hz");
        }

        // 设置音频录制回调
        mAudioRecord.setCallback(new AudioRecordManager.Callback() {
            @Override
            public void onAudioData(byte[] data, int size) {
                // 实时音频数据（可以用于波形显示等）
                if (DEBUG) {
                    Log.v(TAG, "音频数据: " + size + " bytes");
                }
            }

            @Override
            public void onSpeechStart() {
                if (DEBUG) {
                    Log.i(TAG, "检测到语音开始");
                }
            }

            @Override
            public void onSpeechEnd(byte[] audioData) {
                if (DEBUG) {
                    Log.i(TAG, "检测到语音结束");
                    Log.d(TAG, "  - 音频数据: " + audioData.length + " bytes");
                }

                // 发送到 ASR 识别
                if (!mIsRecognizing.get()) {
                    recognizeAudio(audioData);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "AudioRecord 错误: " + error);
                notifyError("录音错误: " + error);
            }
        });

        // 启动录音
        boolean started = mAudioRecord.startListening();
        if (started) {
            mIsEnabled.set(true);
            mIsListening.set(true);
            Log.i(TAG, "软唤醒检测已启动");
        } else {
            notifyError("启动录音失败");
        }

        return started;
    }

    /**
     * 停止软唤醒检测
     */
    public void stop() {
        if (!mIsEnabled.get()) {
            Log.w(TAG, "软唤醒检测未运行");
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "========== 停止软唤醒检测 ==========");
        }

        mIsEnabled.set(false);
        mIsListening.set(false);

        // 停止录音
        if (mAudioRecord != null) {
            mAudioRecord.stopListening();
        }

        // 取消正在进行的识别
        // TODO: ASR 客户端暂未使用
        // if (mIsRecognizing.get()) {
        //     mAsrClient.cancel();
        //     mIsRecognizing.set(false);
        // }

        Log.i(TAG, "软唤醒检测已停止");
    }

    /**
     * 暂停检测
     */
    public void pause() {
        if (!mIsEnabled.get()) {
            Log.w(TAG, "软唤醒检测未运行，无法暂停");
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "暂停软唤醒检测");
        }

        mIsListening.set(false);
        if (mAudioRecord != null) {
            mAudioRecord.pauseListening();
        }
    }

    /**
     * 恢复检测
     */
    public void resume() {
        if (!mIsEnabled.get()) {
            Log.w(TAG, "软唤醒检测未运行，无法恢复");
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "恢复软唤醒检测");
        }

        mIsListening.set(true);
        if (mAudioRecord != null) {
            mAudioRecord.resumeListening();
        }
    }

    /**
     * 识别音频
     *
     * @param audioData 音频数据
     */
    private void recognizeAudio(byte[] audioData) {
        if (mAsrClient == null) {
            notifyError("ASR 客户端未初始化");
            return;
        }

        if (!mIsEnabled.get()) {
            if (DEBUG) {
                Log.d(TAG, "检测已停止，跳过识别");
            }
            return;
        }

        if (!mIsRecognizing.compareAndSet(false, true)) {
            if (DEBUG) {
                Log.w(TAG, "正在识别中，跳过");
            }
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "开始识别音频...");
        }

        // TODO: ASR 客户端暂未使用，唤醒词功能暂时禁用
        // 设置 ASR 回调
        // mAsrClient.setCallback(new MiniMaxASRClient.Callback() {
        //     @Override
        //     public void onResult(String text) {
        //         mIsRecognizing.set(false);
        //
        //         if (DEBUG) {
        //             Log.i(TAG, "========================================");
        //             Log.i(TAG, "ASR 识别结果: " + text);
        //             Log.i(TAG, "========================================");
        //         }
        //
        //         // 分析识别结果
        //         analyzeResult(text);
        //
        //     }
        //
        //     @Override
        //     public void onError(String error) {
        //         mIsRecognizing.set(false);
        //         Log.e(TAG, "ASR 识别错误: " + error);
        //
        //         // 不触发错误回调，因为可能是正常情况（如无语音、超时等）
        //         if (DEBUG) {
        //             Log.d(TAG, "识别失败，但继续监听");
        //         }
        //     }
        // });
        //
        // // 开始识别
        // mAsrClient.recognize(audioData, mAudioRecord.getSampleRate());
        mIsRecognizing.set(false);
    }

    /**
     * 分析识别结果
     *
     * <p>检查识别文本是否包含唤醒词，并提取语音命令。
     *
     * @param text 识别文本
     */
    private void analyzeResult(String text) {
        if (text == null || text.isEmpty()) {
            if (DEBUG) {
                Log.w(TAG, "识别结果为空");
            }
            return;
        }

        // 转换为小写进行比较（忽略大小写）
        String lowerText = text.toLowerCase();
        String lowerWakeword = mWakeword.toLowerCase();

        // 检查是否包含唤醒词
        boolean containsWakeword = lowerText.contains(lowerWakeword);

        if (DEBUG) {
            Log.i(TAG, "分析识别结果:");
            Log.i(TAG, "  - 原始文本: " + text);
            Log.i(TAG, "  - 唤醒词: " + mWakeword);
            Log.i(TAG, "  - 包含唤醒词: " + containsWakeword);
        }

        if (containsWakeword) {
            // 唤醒词检测成功
            Log.i(TAG, "##############################");
            Log.i(TAG, "# 唤醒词检测成功: " + mWakeword);
            Log.i(TAG, "##############################");

            // 提取唤醒词之后的内容作为命令
            String command = extractCommand(text, mWakeword);

            // 回调唤醒词检测
            if (mCallback != null) {
                mCallback.onWakewordDetected();
            }

            // 如果有命令内容，回调语音命令
            if (command != null && !command.isEmpty()) {
                if (DEBUG) {
                    Log.i(TAG, "提取到语音命令: " + command);
                }
                if (mCallback != null) {
                    mCallback.onVoiceCommand(command);
                }
            }

        } else {
            // 没有检测到唤醒词
            if (DEBUG) {
                Log.d(TAG, "未检测到唤醒词，继续监听...");
            }
        }
    }

    /**
     * 提取唤醒词后的命令
     *
     * @param text 完整识别文本
     * @param wakeword 唤醒词
     * @return 唤醒词后的命令文本
     */
    private String extractCommand(String text, String wakeword) {
        // 找到唤醒词的位置
        int wakewordIndex = text.toLowerCase().indexOf(wakeword.toLowerCase());

        if (wakewordIndex == -1) {
            return null;
        }

        // 唤醒词结束位置
        int commandStart = wakewordIndex + wakeword.length();

        // 去除唤醒词后的空格和标点
        while (commandStart < text.length()) {
            char c = text.charAt(commandStart);
            if (c == ' ' || c == ',' || c == '，' || c == '。' || c == '、') {
                commandStart++;
            } else {
                break;
            }
        }

        if (commandStart >= text.length()) {
            return null;
        }

        return text.substring(commandStart).trim();
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return mIsEnabled.get();
    }

    /**
     * 是否正在监听
     */
    public boolean isListening() {
        return mIsListening.get();
    }

    /**
     * 是否有录音权限
     */
    public boolean hasPermission() {
        return mAudioRecord != null && mAudioRecord.hasAudioPermission();
    }

    /**
     * 通知错误
     */
    private void notifyError(String error) {
        if (mCallback != null) {
            mCallback.onError(error);
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stop();
        if (mAudioRecord != null) {
            mAudioRecord.release();
        }
        Log.i(TAG, "SoftWakeupDetector 已释放");
    }

    /**
     * 获取诊断信息
     */
    public String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("SoftWakeupDetector Diagnostics:\n");
        sb.append("  version: 1.0\n");
        sb.append("  isRunning: ").append(mIsEnabled.get()).append("\n");
        sb.append("  isListening: ").append(mIsListening.get()).append("\n");
        sb.append("  isRecognizing: ").append(mIsRecognizing.get()).append("\n");
        sb.append("  wakeword: \"").append(mWakeword).append("\"\n");
        sb.append("  hasPermission: ").append(hasPermission()).append("\n");
        if (mAudioRecord != null) {
            sb.append("\n").append(mAudioRecord.getDiagnosticInfo());
        }
        return sb.toString();
    }
}
