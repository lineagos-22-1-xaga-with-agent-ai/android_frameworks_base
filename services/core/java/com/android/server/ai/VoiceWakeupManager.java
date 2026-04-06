package com.android.server.ai;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.server.LocalServices;
import com.android.server.UiThread;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音唤醒管理器 (VoiceWakeupManager)
 *
 * <p>负责管理设备的语音唤醒功能，作为 AI Agent 与系统语音交互服务之间的桥梁。
 * 该类主要负责：
 * <ul>
 *   <li>语音唤醒的启用/禁用状态管理</li>
 *   <li>热词（唤醒词）的设置与管理</li>
 *   <li>与系统 VoiceInteractionManagerService 集成</li>
 *   <li>接收语音识别结果并分发给 Agent 处理</li>
 *   <li>与系统设置持久化存储的交互</li>
 * </ul>
 *
 * <p>该类采用组合模式，与系统级 VoiceInteractionManagerService 协同工作：
 * <ul>
 *   <li>通过 LocalServices 获取 VoiceInteractionManagerInternal 服务</li>
 *   <li>使用系统级热词检测机制 (SoundTrigger)</li>
 *   <li>通过 VoiceInteractionService 处理语音命令</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>
 * // 1. 创建并初始化
 * VoiceWakeupManager manager = new VoiceWakeupManager(context);
 *
 * // 2. 设置回调监听器
 * manager.setListener(new VoiceWakeupListener() {
 *     {@literal @}Override
 *     public void onWakewordDetected(String hotword) {
 *         // 唤醒词被检测到，准备接收语音命令
 *     }
 *
 *     {@literal @}Override
 *     public void onVoiceCommand(String command) {
 *         // 处理语音转写后的文本命令
 *         agentService.sendTask(command, callback);
 *     }
 *
 *     {@literal @}Override
 *     public void onError(String error) {
 *         // 处理错误
 *     }
 * });
 *
 * // 3. 启用语音唤醒
 * manager.setEnabled(true);
 *
 * // 4. 开始监听
 * manager.startListening();
 * </pre>
 *
 * @author Android System
 * @see VoiceWakeupListener
 * @see VoiceInteractionManagerInternal
 */
public class VoiceWakeupManager {
    /** 日志标签 - 用于区分不同模块的日志输出 */
    private static final String TAG = "VoiceWakeup";
    /** 是否启用调试日志 - 生产环境应设置为 false */
    private static final boolean DEBUG = true;

    /** 语音唤醒Intent Action - 触发系统语音交互 */
    private static final String ACTION_VOICE_WAKEUP = "android.intent.action.VOICE_WAKEUP";

    /** 默认热词 - 当用户未自定义设置时使用 */
    private static final String DEFAULT_HOTWORD = "hey assistant";

    /** 应用程序上下文 - 用于访问系统服务和资源 */
    private final Context mContext;
    /** UI线程执行器 - 用于在主线程执行回调，避免线程安全问题 */
    private final Executor mExecutor;
    /** 主线程Handler - 用于发送消息到主线程队列 */
    private final Handler mHandler;

    /** 语音唤醒事件监听器 - 接收唤醒检测和语音命令事件 */
    private VoiceWakeupListener mListener;
    /** 当前配置的热词（唤醒词）- 用户自定义的唤醒短语 */
    private String mHotword;
    /** 语音唤醒功能是否启用 - 内存缓存状态 */
    private boolean mEnabled = false;
    /** 是否正在监听状态 - 标识当前是否处于监听模式 */
    private final AtomicBoolean mIsListening = new AtomicBoolean(false);

    /** 同步锁对象 - 用于保护多线程访问的共享状态 */
    private final Object mLock = new Object();

    /** 软唤醒检测器 - 当系统没有热词模型时使用 */
    private SoftWakeupDetector mSoftWakeupDetector;
    /** MiniMax API Key - 用于软唤醒的语音识别 */
    private String mApiKey;
    /** 是否使用软唤醒模式 */
    private boolean mUseSoftWakeup = false;

    /**
     * 语音唤醒事件监听器接口
     *
     * <p>当语音唤醒相关事件发生时，会通过此接口回调通知调用者。
     * 所有回调方法都在主线程被调用，监听器可以安全地更新 UI。
     *
     * <p>事件触发顺序：
     * <ol>
     *   <li>{@link #onWakewordDetected} - 检测到唤醒词</li>
     *   <li>{@link #onVoiceCommand} - 接收到语音转写文本</li>
     *   <li>{@link #onError} - 如果发生错误</li>
     * </ol>
     */
    public interface VoiceWakeupListener {
        /**
         * 当检测到唤醒词时调用
         *
         * <p>当用户说出配置的唤醒词时，系统会触发此回调。
         * 这表示用户想要与 AI 助手交互。
         *
         * @param hotword 被检测到的唤醒词（与配置的 mHotword 相同）
         */
        void onWakewordDetected(String hotword);

        /**
         * 当接收到语音命令时调用
         *
         * <p>用户说完语音命令后，语音识别服务会返回转写文本。
         * 调用者通常会将此文本发送给 AI 模型进行处理。
         *
         * @param command 语音转写后的文本命令（用户说的话）
         */
        void onVoiceCommand(String command);

        /**
         * 当发生错误时调用
         *
         * @param error 错误描述信息，包含错误原因便于调试
         */
        void onError(String error);
    }

    /**
     * 内部语音交互回调
     *
     * <p>实现与系统 VoiceInteractionManagerInternal 的交互。
     * 当检测到唤醒词或语音命令时，系统会回调这些方法。
     */
    private final class InternalVoiceCallback {
        /**
         * 处理热词检测结果
         *
         * @param recognized true 表示检测到唤醒词，false 表示误检
         * @param soundModelHandle 声音模型的句柄
         * @param config 检测配置信息
         * @param captureSession 捕获会话（如有）
         */
        void onHotwordDetection(int soundModelHandle, boolean recognized,
                android.os.PersistableBundle config, IBinder captureSession) {
            if (DEBUG) {
                Log.d(TAG, "onHotwordDetection: recognized=" + recognized
                    + ", soundModelHandle=" + soundModelHandle);
            }

            if (recognized) {
                // 在主线程回调监听器的 onWakewordDetected
                mHandler.post(() -> {
                    if (mListener != null) {
                        if (DEBUG) {
                            Log.i(TAG, "唤醒词检测成功，准备接收语音命令");
                        }
                        mListener.onWakewordDetected(mHotword);
                    }
                });
            }
        }

        /**
         * 处理语音识别结果
         *
         * @param text 语音转写文本
         * @param isFinal true 表示最终结果，false 表示中间结果
         * @param timeout 是否超时
         */
        void onVoiceRecognitionResult(String text, boolean isFinal, boolean timeout) {
            if (DEBUG) {
                Log.d(TAG, "onVoiceRecognitionResult: text=" + text
                    + ", isFinal=" + isFinal + ", timeout=" + timeout);
            }

            // 只有最终结果才通知监听器
            if (isFinal && text != null && !text.isEmpty()) {
                mHandler.post(() -> {
                    if (mListener != null) {
                        if (DEBUG) {
                            Log.i(TAG, "收到语音命令: " + text);
                        }
                        mListener.onVoiceCommand(text);
                    }
                });
            }

            // 超时处理
            if (timeout) {
                if (DEBUG) {
                    Log.w(TAG, "语音识别超时");
                }
                mIsListening.set(false);
            }
        }
    }

    private final InternalVoiceCallback mInternalCallback = new InternalVoiceCallback();

    /**
     * 构造函数
     *
     * <p>初始化语音唤醒管理器，包括：
     * <ul>
     *   <li>保存应用程序上下文</li>
     *   <li>初始化 UI 线程执行器和 Handler</li>
     *   <li>从系统设置中加载保存的配置</li>
     *   <li>注册系统服务监听</li>
     * </ul>
     *
     * @param context 应用程序上下文，不能为null
     * @throws IllegalArgumentException if context is null
     */
    public VoiceWakeupManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }

        mContext = context;
        // 使用 UiThread 的 Handler 确保回调在主线程执行
        mExecutor = command -> UiThread.getHandler().post(command);
        mHandler = new Handler(Looper.getMainLooper());

        // 从系统设置中加载保存的配置（启用状态和热词）
        loadSettings();

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "VoiceWakeupManager 初始化完成");
            Log.i(TAG, "  - 调试日志: 启用");
            Log.i(TAG, "  - 默认热词: " + DEFAULT_HOTWORD);
            Log.i(TAG, "  - 当前热词: " + mHotword);
            Log.i(TAG, "  - 启用状态: " + (mEnabled ? "已启用" : "已禁用"));
            Log.i(TAG, "========================================");
        }
    }

    /**
     * 设置语音唤醒事件监听器
     *
     * <p>设置后，当检测到唤醒词或收到语音命令时，会回调对应方法。
     * 设置为 null 可清除监听器。
     *
     * <p>注意事项：
     * <ul>
     *   <li>建议在启动监听前设置监听器</li>
     *   <li>清除监听器不会停止正在进行的监听</li>
     *   <li>监听器方法在主线程被调用</li>
     * </ul>
     *
     * @param listener 语音唤醒事件监听器，传入null可清除监听器
     */
    public void setListener(VoiceWakeupListener listener) {
        synchronized (mLock) {
            if (listener != mListener) {
                if (DEBUG) {
                    Log.d(TAG, "设置语音唤醒监听器: " + (listener != null ? "已设置" : "已清除"));
                }
                mListener = listener;
            } else {
                if (DEBUG) {
                    Log.d(TAG, "监听器未变化，无需更新");
                }
            }
        }
    }

    /**
     * 获取当前设置的监听器
     *
     * @return 当前监听器，可能为null
     */
    public VoiceWakeupListener getListener() {
        synchronized (mLock) {
            return mListener;
        }
    }

    /**
     * 设置语音唤醒功能的启用状态
     *
     * <p>当启用状态改变时：
     * <ol>
     *   <li>更新内存中的 mEnabled 标志</li>
     *   <li>将新状态持久化到系统设置 (Settings.Global)</li>
     *   <li>如果启用，自动开始监听（如之前已在监听则忽略）</li>
     *   <li>如果禁用，停止当前监听</li>
     * </ol>
     *
     * <p>持久化存储的 Key: {@link Settings.Global#AGENT_VOICE_WAKEUP_ENABLED}
     *
     * @param enabled true表示启用语音唤醒，false表示禁用
     */
    public void setEnabled(boolean enabled) {
        synchronized (mLock) {
            boolean oldEnabled = mEnabled;
            mEnabled = enabled;

            // 持久化到系统设置
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AGENT_VOICE_WAKEUP_ENABLED, enabled ? 1 : 0);

            if (DEBUG) {
                Log.i(TAG, "语音唤醒启用状态变更: " + (oldEnabled ? "启用" : "禁用")
                    + " -> " + (enabled ? "启用" : "禁用"));
            }

            // 状态变更时自动处理监听
            if (enabled && !oldEnabled) {
                // 从禁用变为启用，自动开始监听
                if (DEBUG) {
                    Log.i(TAG, "语音唤醒已启用，自动开始监听");
                }
                // 注意：不在锁内调用 startListening，避免死锁
                mHandler.post(() -> startListeningInternal());
            } else if (!enabled && oldEnabled) {
                // 从启用变为禁用，停止监听
                if (DEBUG) {
                    Log.i(TAG, "语音唤醒已禁用，停止监听");
                }
                mHandler.post(() -> stopListeningInternal());
            }
        }
    }

    /**
     * 获取语音唤醒功能的当前启用状态
     *
     * <p>返回内存中缓存的启用状态。
     * 此状态在 {@link #loadSettings()} 或 {@link #setEnabled(boolean)} 时更新。
     *
     * @return true表示语音唤醒已启用，false表示禁用
     */
    public boolean isEnabled() {
        synchronized (mLock) {
            if (DEBUG) {
                Log.v(TAG, "查询语音唤醒状态: " + (mEnabled ? "已启用" : "已禁用"));
            }
            return mEnabled;
        }
    }

    /**
     * 设置唤醒热词
     *
     * <p>热词是用于唤醒语音助手的关键词语，用于触发 AI 助手开始接收语音命令。
     * 设置后会自动持久化到系统设置中。
     *
     * <p>默认热词为 "hey assistant"，用户可根据喜好自定义。
     *
     * <p>持久化存储的 Key: {@link Settings.Global#AGENT_HOTWORD}
     *
     * @param hotword 新的唤醒热词，不能为null或空字符串
     * @throws IllegalArgumentException if hotword is null or empty
     */
    public void setHotword(String hotword) {
        if (hotword == null || hotword.trim().isEmpty()) {
            if (DEBUG) {
                Log.w(TAG, "设置热词失败: 热词不能为空");
            }
            throw new IllegalArgumentException("Hotword cannot be null or empty");
        }

        String trimmedHotword = hotword.trim();
        synchronized (mLock) {
            mHotword = trimmedHotword;

            // 持久化到系统设置
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.AGENT_HOTWORD, trimmedHotword);

            if (DEBUG) {
                Log.i(TAG, "热词已设置为: \"" + trimmedHotword + "\""
                    + " (长度: " + trimmedHotword.length() + " 字符)");
            }

            // 通知监听器热词已变更
            mHandler.post(() -> {
                synchronized (mLock) {
                    if (mListener != null) {
                        if (DEBUG) {
                            Log.i(TAG, "通知监听器热词已变更");
                        }
                        // 注意：这里只通知热词变更，不触发完整回调
                    }
                }
            });
        }
    }

    /**
     * 获取当前配置的唤醒热词
     *
     * <p>返回当前内存中缓存的热词。
     * 如果未设置（null 或空），返回默认值 "hey assistant"。
     *
     * @return 当前的热词，不会返回 null
     */
    public String getHotword() {
        synchronized (mLock) {
            if (mHotword == null || mHotword.isEmpty()) {
                if (DEBUG) {
                    Log.w(TAG, "热词未设置或为空，返回默认值");
                }
                return DEFAULT_HOTWORD;
            }
            if (DEBUG) {
                Log.v(TAG, "查询当前热词: " + mHotword);
            }
            return mHotword;
        }
    }

    /**
     * 开始语音监听（对外公开接口）
     *
     * <p>启动系统的语音唤醒功能，开始监听用户的语音输入。
     *
     * <p>调用此方法前应确保：
     * <ol>
     *   <li>语音唤醒功能已启用 ({@link #setEnabled(true)})</li>
     *   <li>已设置监听器 ({@link #setListener})</li>
     *   <li>应用有 RECORD_AUDIO 权限</li>
     * </ol>
     *
     * <p>此方法是异步的，不会阻塞调用线程。
     *
     * @see #stopListening()
     * @see #isListening()
     */
    public void startListening() {
        if (DEBUG) {
            Log.i(TAG, "========== 开始语音监听 ==========");
            Log.d(TAG, "请求开始语音监听");
        }

        // 线程安全检查
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.w(TAG, "语音唤醒功能未启用，无法启动监听");
                }
                notifyErrorLocked("语音唤醒功能未启用");
                return;
            }

            if (mListener == null) {
                if (DEBUG) {
                    Log.w(TAG, "未设置监听器，无法启动监听");
                }
                notifyErrorLocked("未设置监听器");
                return;
            }
        }

        startListeningInternal();
    }

    /**
     * 开始语音监听（内部实现）
     *
     * <p>实际执行监听启动逻辑，包括：
     * <ol>
     *   <li>检查是否已在监听中</li>
     *   <li>判断使用原生热词还是软唤醒</li>
     *   <li>启动相应的监听模式</li>
     *   <li>更新监听状态</li>
     * </ol>
     */
    private void startListeningInternal() {
        // 使用 AtomicBoolean 确保线程安全
        if (!mIsListening.compareAndSet(false, true)) {
            if (DEBUG) {
                Log.w(TAG, "已在监听中，无需重复启动");
            }
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "正在启动语音监听...");
            Log.d(TAG, "  - 热词: " + getHotword());
            Log.d(TAG, "  - 监听器: " + (mListener != null ? "已设置" : "未设置"));
        }

        // 判断使用哪种唤醒方式
        mUseSoftWakeup = !hasNativeHotwordSupport();

        if (DEBUG) {
            Log.i(TAG, "唤醒模式: " + (mUseSoftWakeup ? "软唤醒 (MiniMax ASR)" : "原生热词"));
        }

        if (mUseSoftWakeup) {
            // 使用软唤醒模式
            startSoftWakeup();
        } else {
            // 使用原生热词模式
            startNativeWakeup();
        }
    }

    /**
     * 启动原生热词唤醒
     *
     * <p>使用系统 VoiceInteractionService 进行热词检测。
     */
    private void startNativeWakeup() {
        if (DEBUG) {
            Log.i(TAG, "启动原生热词唤醒...");
        }

        try {
            // 发送 ACTION_VOICE_WAKEUP Intent 启动系统语音交互
            Intent intent = new Intent(ACTION_VOICE_WAKEUP);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);

            if (DEBUG) {
                Log.i(TAG, "系统语音交互 Intent 已发送");
            }

            notifyListeningStarted();

        } catch (SecurityException e) {
            Log.e(TAG, "启动原生唤醒失败: 权限不足", e);
            mIsListening.set(false);
            // 降级到软唤醒
            Log.i(TAG, "降级到软唤醒模式...");
            fallbackToSoftWakeup();

        } catch (Exception e) {
            Log.e(TAG, "启动原生唤醒失败", e);
            mIsListening.set(false);
            fallbackToSoftWakeup();
        }
    }

    /**
     * 启动软唤醒
     *
     * <p>使用麦克风 + MiniMax ASR 进行软件级唤醒词检测。
     */
    private void startSoftWakeup() {
        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "启动软唤醒模式");
            Log.i(TAG, "  - 唤醒词: " + mHotword);
            Log.i(TAG, "  - API Key: " + (mApiKey != null && !mApiKey.isEmpty() ? "已设置" : "未设置"));
            Log.i(TAG, "========================================");
        }

        // 检查 API Key
        if (mApiKey == null || mApiKey.isEmpty()) {
            Log.e(TAG, "软唤醒需要 MiniMax API Key");
            mIsListening.set(false);
            notifyError("软唤醒需要 MiniMax API Key，请先在设置中配置");
            return;
        }

        // 注意: system_server 进程有足够权限，无需检查

        try {
            // 创建或获取软唤醒检测器
            if (mSoftWakeupDetector == null) {
                mSoftWakeupDetector = new SoftWakeupDetector(mContext, mApiKey);
                mSoftWakeupDetector.setWakeword(mHotword);

                // 设置回调
                mSoftWakeupDetector.setCallback(new SoftWakeupDetector.Callback() {
                    @Override
                    public void onWakewordDetected() {
                        Log.i(TAG, "$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
                        Log.i(TAG, "$$ 软唤醒成功: " + mHotword + " $$$");
                        Log.i(TAG, "$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
                        notifyWakewordDetected(mHotword);
                    }

                    @Override
                    public void onVoiceCommand(String text) {
                        Log.i(TAG, "收到语音命令: " + text);
                        notifyVoiceCommand(text);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "软唤醒错误: " + error);
                        notifyError(error);
                    }
                });
            } else {
                // 更新 API Key 和唤醒词
                mSoftWakeupDetector.setApiKey(mApiKey);
                mSoftWakeupDetector.setWakeword(mHotword);
            }

            // 启动软唤醒
            boolean started = mSoftWakeupDetector.start();
            if (started) {
                if (DEBUG) {
                    Log.i(TAG, "软唤醒检测器已启动");
                }
                notifyListeningStarted();
            } else {
                Log.e(TAG, "软唤醒检测器启动失败");
                mIsListening.set(false);
                notifyError("软唤醒检测器启动失败");
            }

        } catch (Exception e) {
            Log.e(TAG, "启动软唤醒失败", e);
            mIsListening.set(false);
            notifyError("启动软唤醒失败: " + e.getMessage());
        }
    }

    /**
     * 降级到软唤醒
     *
     * <p>当原生热词唤醒失败时，自动切换到软唤醒模式。
     */
    private void fallbackToSoftWakeup() {
        mUseSoftWakeup = true;
        Log.i(TAG, "========== 降级到软唤醒模式 ==========");
        startSoftWakeup();
    }

    /**
     * 通知监听开始
     */
    private void notifyListeningStarted() {
        synchronized (mLock) {
            if (mListener != null) {
                mHandler.post(() -> {
                    synchronized (mLock) {
                        if (mListener != null) {
                            if (DEBUG) {
                                Log.i(TAG, "通知监听器：开始监听模式 ("
                                    + (mUseSoftWakeup ? "软唤醒" : "原生") + ")");
                            }
                        }
                    }
                });
            }
        }
    }

    /**
     * 停止语音监听（对外公开接口）
     *
     * <p>停止当前正在进行的语音监听操作。
     * 调用此方法后，系统会停止接收语音输入。
     *
     * <p>此方法是异步的，不会阻塞调用线程。
     *
     * @see #startListening()
     * @see #isListening()
     */
    public void stopListening() {
        if (DEBUG) {
            Log.i(TAG, "========== 停止语音监听 ==========");
            Log.d(TAG, "请求停止语音监听");
        }

        stopListeningInternal();
    }

    /**
     * 停止语音监听（内部实现）
     *
     * <p>实际执行监听停止逻辑，包括：
     * <ol>
     *   <li>检查是否在监听中</li>
     *   <li>停止软唤醒检测器（如果是软唤醒模式）</li>
     *   <li>更新监听状态</li>
     *   <li>通知监听器</li>
     * </ol>
     */
    private void stopListeningInternal() {
        if (!mIsListening.get()) {
            if (DEBUG) {
                Log.w(TAG, "不在监听状态，无需停止");
            }
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "正在停止语音监听...");
            Log.d(TAG, "  - 模式: " + (mUseSoftWakeup ? "软唤醒" : "原生"));
        }

        try {
            // 如果是软唤醒模式，停止软唤醒检测器
            if (mUseSoftWakeup && mSoftWakeupDetector != null) {
                if (DEBUG) {
                    Log.d(TAG, "停止软唤醒检测器...");
                }
                mSoftWakeupDetector.stop();
            }

            // 重置监听状态
            mIsListening.set(false);

            // 通知监听器停止监听
            synchronized (mLock) {
                if (mListener != null) {
                    mHandler.post(() -> {
                        synchronized (mLock) {
                            if (mListener != null) {
                                if (DEBUG) {
                                    Log.i(TAG, "通知监听器：停止监听模式");
                                }
                            }
                        }
                    });
                }
            }

            if (DEBUG) {
                Log.i(TAG, "语音监听已停止");
            }

        } catch (Exception e) {
            Log.e(TAG, "停止语音监听时发生异常: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 查询当前是否正在监听
     *
     * @return true 表示正在监听，false 表示未监听
     */
    public boolean isListening() {
        boolean listening = mIsListening.get();
        if (DEBUG) {
            Log.v(TAG, "查询监听状态: " + (listening ? "正在监听" : "未监听"));
        }
        return listening;
    }

    /**
     * 处理语音输入
     *
     * <p>当接收到语音转写结果时调用此方法进行处理。
     * 会将转写文本作为语音命令通知给监听器。
     *
     * <p>此方法通常由系统语音识别服务回调，
     * 将语音识别结果分发给 Agent 进行处理。
     *
     * @param transcription 语音识别转写后的文本内容
     */
    public void processVoiceInput(String transcription) {
        if (transcription == null || transcription.trim().isEmpty()) {
            if (DEBUG) {
                Log.w(TAG, "收到空语音输入，忽略");
            }
            return;
        }

        String trimmedText = transcription.trim();
        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "收到语音输入");
            Log.i(TAG, "  - 原始文本: " + transcription);
            Log.i(TAG, "  - 清理后: " + trimmedText);
            Log.i(TAG, "========================================");
        }

        // 将语音命令回调给监听器
        synchronized (mLock) {
            if (mListener != null) {
                // 在主线程回调
                mHandler.post(() -> {
                    synchronized (mLock) {
                        if (mListener != null) {
                            if (DEBUG) {
                                Log.i(TAG, "回调监听器: onVoiceCommand(\"" + trimmedText + "\")");
                            }
                            mListener.onVoiceCommand(trimmedText);
                        }
                    }
                });
            } else {
                if (DEBUG) {
                    Log.w(TAG, "无监听器，忽略语音输入");
                }
            }
        }

        // 语音命令处理完成后，重置监听状态
        // 注意：某些实现可能需要保持监听状态
        if (DEBUG) {
            Log.d(TAG, "语音命令已处理，等待下一轮唤醒");
        }
    }

    /**
     * 唤醒词检测事件
     *
     * <p>当系统检测到用户说出唤醒词时调用此方法。
     * 检测到唤醒词后，会在主线程回调监听器的 onWakewordDetected 方法。
     *
     * <p>此方法通常由系统热词检测服务回调。
     *
     * @param hotword 被检测到的唤醒词（可选，用于确认是哪个热词被检测到）
     */
    public void onWakewordDetected(String hotword) {
        if (DEBUG) {
            Log.i(TAG, "########################################");
            Log.i(TAG, "检测到唤醒词！");
            Log.i(TAG, "  - 热词: " + hotword);
            Log.i(TAG, "  - 配置热词: " + getHotword());
            Log.i(TAG, "########################################");
        }

        // 确保回调在主线程执行
        synchronized (mLock) {
            if (mListener != null) {
                String detectedHotword = (hotword != null && !hotword.isEmpty()) ? hotword : getHotword();
                mHandler.post(() -> {
                    synchronized (mLock) {
                        if (mListener != null) {
                            if (DEBUG) {
                                Log.i(TAG, "回调监听器: onWakewordDetected(\"" + detectedHotword + "\")");
                            }
                            mListener.onWakewordDetected(detectedHotword);
                        }
                    }
                });
            }
        }
    }

    /**
     * 热词检测准备好（由系统调用）
     *
     * <p>当热词检测服务准备好接收语音时调用。
     */
    public void onHotwordDetectionReady() {
        if (DEBUG) {
            Log.i(TAG, "热词检测服务已就绪");
        }
    }

    /**
     * 热词检测失败（由系统调用）
     *
     * @param error 错误描述
     */
    public void onHotwordDetectionFailure(String error) {
        Log.e(TAG, "热词检测失败: " + error);

        mIsListening.set(false);

        synchronized (mLock) {
            if (mListener != null) {
                mHandler.post(() -> {
                    synchronized (mLock) {
                        if (mListener != null) {
                            mListener.onError("热词检测失败: " + error);
                        }
                    }
                });
            }
        }
    }

    /**
     * 从系统设置加载语音唤醒配置
     *
     * <p>从 Settings.Global 中读取语音唤醒的启用状态和热词配置。
     * 如果热词未设置或为空，则使用默认值 "hey assistant"。
     *
     * <p>读取的设置：
     * <ul>
     *   <li>{@link Settings.Global#AGENT_VOICE_WAKEUP_ENABLED} - 启用状态</li>
     *   <li>{@link Settings.Global#AGENT_HOTWORD} - 热词配置</li>
     *   <li>{@link Settings.Global#MINIMAX_API_KEY} - MiniMax API Key</li>
     * </ul>
     *
     * <p>注意：此方法在构造函数中自动调用。
     */
    public void loadSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        // 读取启用状态设置（默认为禁用）
        int enabledValue = Settings.Global.getInt(resolver,
                Settings.Global.AGENT_VOICE_WAKEUP_ENABLED, 0);
        mEnabled = (enabledValue == 1);

        // 读取热词设置
        mHotword = Settings.Global.getString(resolver,
                Settings.Global.AGENT_HOTWORD);

        // 如果热词未设置或为空，使用默认值
        if (mHotword == null || mHotword.trim().isEmpty()) {
            mHotword = DEFAULT_HOTWORD;
            // 持久化默认值，避免每次都查询
            Settings.Global.putString(resolver,
                    Settings.Global.AGENT_HOTWORD, DEFAULT_HOTWORD);
            if (DEBUG) {
                Log.i(TAG, "热词未设置，使用默认值: \"" + DEFAULT_HOTWORD + "\"");
            }
        }

        // 读取 MiniMax API Key
        mApiKey = Settings.Global.getString(resolver,
                Settings.Global.MINIMAX_API_KEY);

        if (DEBUG) {
            Log.i(TAG, "已加载语音唤醒设置:");
            Log.i(TAG, "  - 启用状态: " + (mEnabled ? "启用" : "禁用"));
            Log.i(TAG, "  - 热词: \"" + mHotword + "\"");
            Log.i(TAG, "  - API Key: " + (mApiKey != null && !mApiKey.isEmpty() ? "已设置" : "未设置"));
            Log.i(TAG, "  - 监听状态: " + (mIsListening.get() ? "监听中" : "未监听"));
        }
    }

    /**
     * 检查系统是否支持原生热词唤醒
     *
     * <p>检测设备是否有注册的热词模型。
     * 如果没有，则需要使用软唤醒（MiniMax ASR）。
     *
     * @return true 表示有原生热词支持
     */
    public boolean hasNativeHotwordSupport() {
        // TODO: 通过 VoiceInteractionManagerService 检查是否有注册的热词模型
        // 目前暂时返回 false，强制使用软唤醒
        if (DEBUG) {
            Log.d(TAG, "检查原生热词支持: 暂不支持，返回 false");
        }
        return false;
    }

    /**
     * 重新加载设置
     *
     * <p>手动触发重新从系统设置加载配置。
     * 用于在外部修改设置后刷新内部状态。
     */
    public void reloadSettings() {
        if (DEBUG) {
            Log.i(TAG, "重新加载语音唤醒设置...");
        }
        loadSettings();
    }

    /**
     * 发送错误通知（线程安全）
     */
    private void notifyError(String error) {
        synchronized (mLock) {
            notifyErrorLocked(error);
        }
    }

    /**
     * 发送错误通知（需在同步块外调用）
     */
    private void notifyErrorLocked(String error) {
        if (mListener != null) {
            mHandler.post(() -> {
                synchronized (mLock) {
                    if (mListener != null) {
                        if (DEBUG) {
                            Log.i(TAG, "通知监听器: onError(\"" + error + "\")");
                        }
                        mListener.onError(error);
                    }
                }
            });
        }
    }

    /**
     * 获取当前状态信息用于调试输出
     *
     * <p>将当前状态追加到提供的 StringBuilder 中。
     * 可用于 dumpstate 或调试信息输出。
     *
     * @param sb StringBuilder对象，用于接收状态信息
     */
    public void dump(StringBuilder sb) {
        synchronized (mLock) {
            sb.append("\n========================================\n");
            sb.append("VoiceWakeupManager 状态信息\n");
            sb.append("========================================\n");
            sb.append("  enabled: ").append(mEnabled).append("\n");
            sb.append("  listening: ").append(mIsListening.get()).append("\n");
            sb.append("  hotword: \"").append(mHotword).append("\"\n");
            sb.append("  hasListener: ").append(mListener != null).append("\n");
            sb.append("  defaultHotword: \"").append(DEFAULT_HOTWORD).append("\"\n");
            sb.append("========================================\n");
        }

        if (DEBUG) {
            Log.d(TAG, "导出状态信息:");
            Log.d(TAG, "  enabled=" + mEnabled);
            Log.d(TAG, "  listening=" + mIsListening.get());
            Log.d(TAG, "  hotword=" + mHotword);
            Log.d(TAG, "  hasListener=" + (mListener != null));
        }
    }

    /**
     * 通知唤醒词检测成功
     *
     * <p>当软唤醒或原生唤醒检测到唤醒词时调用此方法。
     *
     * @param hotword 被检测到的唤醒词
     */
    private void notifyWakewordDetected(String hotword) {
        synchronized (mLock) {
            if (mListener != null) {
                mHandler.post(() -> {
                    synchronized (mLock) {
                        if (mListener != null) {
                            if (DEBUG) {
                                Log.i(TAG, "回调监听器: onWakewordDetected(\"" + hotword + "\")");
                            }
                            mListener.onWakewordDetected(hotword);
                        }
                    }
                });
            }
        }
    }

    /**
     * 通知收到语音命令
     *
     * <p>当收到语音命令时调用此方法。
     *
     * @param command 语音命令文本
     */
    private void notifyVoiceCommand(String command) {
        synchronized (mLock) {
            if (mListener != null) {
                mHandler.post(() -> {
                    synchronized (mLock) {
                        if (mListener != null) {
                            if (DEBUG) {
                                Log.i(TAG, "回调监听器: onVoiceCommand(\"" + command + "\")");
                            }
                            mListener.onVoiceCommand(command);
                        }
                    }
                });
            }
        }
    }

    /**
     * 获取诊断信息
     *
     * <p>返回包含详细诊断信息的字符串，用于问题排查。
     *
     * @return 诊断信息字符串
     */
    public String getDiagnosticInfo() {
        synchronized (mLock) {
            StringBuilder sb = new StringBuilder();
            sb.append("VoiceWakeupManager Diagnostics:\n");
            sb.append("  Version: 3.0 (软唤醒支持)\n");
            sb.append("  Enabled: ").append(mEnabled).append("\n");
            sb.append("  Listening: ").append(mIsListening.get()).append("\n");
            sb.append("  UseSoftWakeup: ").append(mUseSoftWakeup).append("\n");
            sb.append("  Hotword: \"").append(mHotword).append("\"\n");
            sb.append("  DefaultHotword: \"").append(DEFAULT_HOTWORD).append("\"\n");
            sb.append("  HasListener: ").append(mListener != null).append("\n");
            sb.append("  HasApiKey: ").append(mApiKey != null && !mApiKey.isEmpty()).append("\n");
            sb.append("  SoftWakeupDetector: ").append(mSoftWakeupDetector != null ? "initialized" : "null").append("\n");
            sb.append("  Context: ").append(mContext.getPackageName()).append("\n");
            return sb.toString();
        }
    }
}
