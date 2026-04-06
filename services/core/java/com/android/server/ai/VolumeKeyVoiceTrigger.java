package com.android.server.ai;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.android.server.UiThread;

/**
 * 音量键语音触发器 (VolumeKeyVoiceTrigger)
 *
 * <p>当用户长按音量下键时，启动语音助手。
 *
 * <p>工作流程：
 * <ol>
 *   <li>监听音量下键按下事件</li>
 *   <li>开始计时</li>
 *   <li>如果松开前超过 500ms，触发语音助手</li>
 *   <li>如果在 500ms 内松开，不触发</li>
 * </ol>
 *
 * <p>需要权限：
 * <ul>
 *   <li>android.permission.START_ACTIVITY_AS_USER</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>
 * VolumeKeyVoiceTrigger trigger = new VolumeKeyVoiceTrigger(context);
 * trigger.start();
 * </pre>
 *
 * @author Android System
 */
public class VolumeKeyVoiceTrigger {
    private static final String TAG = "VolumeKeyTrigger";

    /** 日志开关 */
    private static final boolean DEBUG = true;

    /** 长按阈值 500ms */
    private static final long LONG_PRESS_THRESHOLD_MS = 500;

    /** 上下文 */
    private final Context mContext;

    /** 主线程 Handler */
    private final Handler mHandler;

    /** 语音输入管理器 (可选，如果设置则使用我们的 ASR) */
    private VoiceInputManager mVoiceInputManager;

    /** 是否启用 */
    private boolean mEnabled = false;

    /** 按下时间戳 */
    private long mDownTime = 0;

    /** 是否正在计数 */
    private boolean mTracking = false;

    /** 检查消息 */
    private static final int MSG_CHECK_LONG_PRESS = 1;

    /**
     * 构造函数
     *
     * @param context 上下文
     */
    public VolumeKeyVoiceTrigger(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_CHECK_LONG_PRESS) {
                    long pressDuration = SystemClock.elapsedRealtime() - mDownTime;
                    if (pressDuration >= LONG_PRESS_THRESHOLD_MS) {
                        Log.i(TAG, "####################################");
                        Log.i(TAG, "# 音量下键长按 " + LONG_PRESS_THRESHOLD_MS + "ms 触发语音助手!");
                        Log.i(TAG, "# 按压时长: " + pressDuration + " ms");
                        Log.i(TAG, "####################################");
                        launchVoiceAssist();
                    }
                }
            }
        };

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "VolumeKeyVoiceTrigger 初始化");
            Log.i(TAG, "  - 长按阈值: " + LONG_PRESS_THRESHOLD_MS + " ms");
            Log.i(TAG, "========================================");
        }
    }

    /**
     * 启用触发器
     */
    public void enable() {
        mEnabled = true;
        if (DEBUG) {
            Log.i(TAG, "音量键语音触发器已启用");
        }
    }

    /**
     * 禁用触发器
     */
    public void disable() {
        mEnabled = false;
        reset();
        if (DEBUG) {
            Log.i(TAG, "音量键语音触发器已禁用");
        }
    }

    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * 处理按键事件
     *
     * <p>应该在 PhoneWindowManager 或 InputManager 中调用此方法。
     *
     * @param event 按键事件
     * @return true 表示事件被消费（触发语音助手），false 表示继续传递
     */
    public boolean onKeyEvent(KeyEvent event) {
        if (!mEnabled) {
            return false;
        }

        int keyCode = event.getKeyCode();

        // 只处理音量下键
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }

        int action = event.getAction();

        if (DEBUG) {
            Log.d(TAG, "收到音量下键事件: " + (action == KeyEvent.ACTION_DOWN ? "按下" : "松开"));
        }

        if (action == KeyEvent.ACTION_DOWN) {
            // 按下
            return handleKeyDown(event);
        } else if (action == KeyEvent.ACTION_UP) {
            // 松开
            return handleKeyUp(event);
        }

        return false;
    }

    /**
     * 处理按键按下
     */
    private boolean handleKeyDown(KeyEvent event) {
        if (mTracking) {
            // 已经在追踪，忽略
            if (DEBUG) {
                Log.d(TAG, "已在追踪中，忽略重复按下");
            }
            return false;
        }

        mDownTime = event.getDownTime();
        mTracking = true;

        if (DEBUG) {
            Log.d(TAG, "开始追踪音量下键, downTime=" + mDownTime);
        }

        // 发送延迟消息检查长按
        mHandler.sendEmptyMessageDelayed(MSG_CHECK_LONG_PRESS, LONG_PRESS_THRESHOLD_MS);

        // 不消费事件，让音量调节正常工作
        return false;
    }

    /**
     * 处理按键松开
     */
    private boolean handleKeyUp(KeyEvent event) {
        if (!mTracking) {
            return false;
        }

        long pressDuration = event.getEventTime() - mDownTime;

        if (DEBUG) {
            Log.d(TAG, "音量下键松开, 按压时长=" + pressDuration + " ms");
        }

        // 取消长按检查
        mHandler.removeMessages(MSG_CHECK_LONG_PRESS);

        // 如果按压时长超过阈值，说明触发了语音助手
        if (pressDuration >= LONG_PRESS_THRESHOLD_MS) {
            // 通知 VoiceInputManager 停止录音并开始识别
            VoiceInputManager vim = mVoiceInputManager;
            if (vim == null) {
                vim = VoiceInputManager.getInstance();
            }
            if (vim != null) {
                vim.stopRecordingAndRecognizeDirect();
            }

            // 重置状态
            reset();

            if (DEBUG) {
                Log.i(TAG, "长按触发，事件已被消费");
            }
            return true; // 消费事件，不传递
        }

        // 重置状态
        reset();

        return false; // 短按，传递事件
    }

    /**
     * 重置状态
     */
    private void reset() {
        mTracking = false;
        mDownTime = 0;
    }

    /**
     * 设置语音输入管理器
     *
     * <p>如果设置了这个，音量键长按将使用我们的 VoiceInputManager 进行录音和 ASR。
     * 否则使用系统默认的语音助手。
     *
     * @param voiceInputManager VoiceInputManager 实例
     */
    public void setVoiceInputManager(VoiceInputManager voiceInputManager) {
        mVoiceInputManager = voiceInputManager;
        if (DEBUG) {
            Log.i(TAG, "VoiceInputManager 已设置: " + (voiceInputManager != null ? "启用" : "禁用"));
        }
    }

    /**
     * 启动语音助手
     */
    private void launchVoiceAssist() {
        // 获取 VoiceInputManager（优先使用注入的，其次使用静态实例）
        VoiceInputManager vim = mVoiceInputManager;
        if (vim == null) {
            vim = VoiceInputManager.getInstance();
        }

        // 如果有 VoiceInputManager，使用我们的 ASR
        if (vim != null) {
            if (DEBUG) {
                Log.i(TAG, "使用 VoiceInputManager 进行语音输入...");
            }
            // 直接开始录音
            vim.startRecordingDirect();
            return;
        }

        // 否则使用系统默认的语音助手
        if (DEBUG) {
            Log.i(TAG, "启动系统语音助手...");
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VOICE_ASSIST);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mContext.startActivityAsUser(intent, android.os.UserHandle.CURRENT);

            if (DEBUG) {
                Log.i(TAG, "语音助手 Intent 已发送");
            }
        } catch (Exception e) {
            Log.e(TAG, "启动语音助手失败", e);
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        disable();
        mHandler.removeCallbacksAndMessages(null);
        if (DEBUG) {
            Log.i(TAG, "VolumeKeyVoiceTrigger 已释放");
        }
    }
}
