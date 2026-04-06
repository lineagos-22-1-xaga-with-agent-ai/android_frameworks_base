package android.app.ai;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ai.IAgentService;
import android.app.ai.IAgentServiceCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.speech.RecognizerIntent;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 语音助手 Activity (VoiceAssistActivity)
 *
 * <p>响应 ACTION_VOICE_ASSIST Intent，提供语音交互界面。
 *
 * @hide
 */
@SuppressLint({"ForbiddenSuperClass", "NotCloseable", "ProtectedMember", "OnNameExpected", "UnflaggedApi"})
public class VoiceAssistActivity extends Activity {
    private static final String TAG = "VoiceAssistActivity";

    /** 日志开关 */
    private static final boolean DEBUG = true;

    /** 语音识别超时时间 (ms) */
    private static final long SPEECH_TIMEOUT_MS = 10000;

    /** Activity 关闭延迟 (ms) */
    private static final long CLOSE_DELAY_MS = 3000;

    /** 搜狗语音输入请求码 */
    private static final int REQUEST_SOGOU_VOICE = 1001;

    /** AI Agent 服务名称 */
    private static final String AGENT_SERVICE_NAME = "agent";

    /** 上下文 */
    private Context mContext;

    /** 主线程 Handler */
    private Handler mHandler;

    /** 语音识别器 */
    private SpeechRecognizer mSpeechRecognizer;

    /** 是否正在监听 */
    private boolean mIsListening = false;

    /** AI Agent 服务绑定 */
    private IAgentService mAgentService;
    private boolean mServiceBound = false;

    /** 搜狗输入法语音输入 */
    private static final String SOGOU_VOICE_PACKAGE = "com.sohu.inputmethod.sogou";
    private static final String SOGOU_VOICE_CLASS = "com.sohu.inputmethod.sogou.voice.VoiceInputActivity";

    /** UI 组件 */
    private TextView mStatusText;
    private TextView mResultText;
    private FrameLayout mRootLayout;
    private View mWaveView;

    /** 识别结果 */
    private String mRecognizedText = "";
    private String mAgentResponse = "";

    /** 超时关闭任务 */
    private Runnable mCloseRunnable;

    /**
     * Activity 创建
     */
    @SuppressLint("MissingNullability")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = this;
        mHandler = new Handler(Looper.getMainLooper());

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "VoiceAssistActivity onCreate");
            Log.i(TAG, "========================================");
        }

        // 设置无窗口边框（透明主题）
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setupFullScreenWindow();

        // 创建 UI
        createUI();

        // 绑定 AI Agent 服务
        bindAgentService();
    }

    /**
     * 设置全屏窗口
     */
    private void setupFullScreenWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        window.setAttributes(params);

        // 设置透明背景
        window.setBackgroundDrawable(new GradientDrawable());
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    /**
     * 创建 UI
     */
    private void createUI() {
        if (DEBUG) {
            Log.d(TAG, "创建语音助手 UI");
        }

        // 根布局
        mRootLayout = new FrameLayout(mContext);
        mRootLayout.setBackgroundColor(Color.parseColor("#E6394646")); // 半透明黑色
        mRootLayout.setClickable(true);
        mRootLayout.setOnClickListener(v -> {
            // 点击背景关闭
            if (DEBUG) {
                Log.d(TAG, "点击背景关闭");
            }
            finish();
        });

        // 中央容器
        FrameLayout centerContainer = new FrameLayout(mContext);
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
        );
        centerParams.gravity = android.view.Gravity.CENTER;
        mRootLayout.addView(centerContainer, centerParams);

        // 状态文本
        mStatusText = new TextView(mContext);
        mStatusText.setText("正在聆听...");
        mStatusText.setTextSize(24);
        mStatusText.setTextColor(Color.WHITE);
        mStatusText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
        );
        statusParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        int statusTopMargin = dpToPx(100);
        statusParams.topMargin = statusTopMargin;
        centerContainer.addView(mStatusText, statusParams);

        // 波形/动画视图 (简化版，用圆形代替)
        mWaveView = createWaveView();
        FrameLayout.LayoutParams waveParams = new FrameLayout.LayoutParams(
                dpToPx(120),
                dpToPx(120)
        );
        waveParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        int waveTopMargin = dpToPx(32);
        waveParams.topMargin = waveTopMargin;
        centerContainer.addView(mWaveView, waveParams);

        // 结果文本
        mResultText = new TextView(mContext);
        mResultText.setText("");
        mResultText.setTextSize(18);
        mResultText.setTextColor(Color.WHITE);
        mResultText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        mResultText.setMaxLines(5);
        FrameLayout.LayoutParams resultParams = new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
        );
        resultParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        int resultTopMargin = dpToPx(48);
        resultParams.topMargin = resultTopMargin;
        int horizontalMargin = dpToPx(32);
        resultParams.leftMargin = horizontalMargin;
        resultParams.rightMargin = horizontalMargin;
        centerContainer.addView(mResultText, resultParams);

        // 提示文本
        TextView hintText = new TextView(mContext);
        hintText.setText("长按音量下键说话");
        hintText.setTextSize(14);
        hintText.setTextColor(Color.parseColor("#AAAAAA"));
        hintText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
        );
        hintParams.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
        int hintBottomMargin = dpToPx(100);
        hintParams.bottomMargin = hintBottomMargin;
        centerContainer.addView(hintText, hintParams);

        setContentView(mRootLayout);
    }

    /**
     * 创建波形视图
     */
    private View createWaveView() {
        View view = new View(mContext);

        // 圆形背景
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(Color.parseColor("#4CAF50")); // 绿色
        drawable.setStroke(dpToPx(4), Color.WHITE);
        view.setBackground(drawable);

        // 启动脉冲动画
        animatePulse(view);

        return view;
    }

    /**
     * 脉冲动画
     */
    private void animatePulse(View view) {
        view.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(500)
                .withEndAction(() -> {
                    view.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(500)
                            .withEndAction(() -> {
                                if (mIsListening) {
                                    animatePulse(view);
                                } else {
                                    view.setScaleX(1.0f);
                                    view.setScaleY(1.0f);
                                }
                            })
                            .start();
                })
                .start();
    }

    /**
     * dp 转 px
     */
    private int dpToPx(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    /**
     * Activity 启动
     */
    @Override
    public void onStart() {
        super.onStart();

        if (DEBUG) {
            Log.i(TAG, "onStart - 开始语音识别");
        }

        // 开始语音识别
        startSpeechRecognition();
    }

    /**
     * Activity 恢复
     */
    @Override
    public void onResume() {
        super.onResume();

        if (DEBUG) {
            Log.d(TAG, "onResume");
        }
    }

    /**
     * Activity 暂停
     */
    @Override
    public void onPause() {
        super.onPause();

        if (DEBUG) {
            Log.d(TAG, "onPause");
        }

        // 停止语音识别
        stopSpeechRecognition();
    }

    /**
     * Activity 销毁
     */
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (DEBUG) {
            Log.i(TAG, "onDestroy");
        }

        // 解除服务绑定
        unbindAgentService();

        // 移除关闭任务
        if (mCloseRunnable != null) {
            mHandler.removeCallbacks(mCloseRunnable);
        }
    }

    /**
     * 开始语音识别
     */
    private void startSpeechRecognition() {
        if (mIsListening) {
            if (DEBUG) {
                Log.w(TAG, "已经在监听中");
            }
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "开始语音识别");
            Log.i(TAG, "========================================");
        }

        // 检查语音识别是否可用
        if (!SpeechRecognizer.isRecognitionAvailable(mContext)) {
            Log.w(TAG, "系统语音识别不可用，使用搜狗输入法语音输入");
            updateStatus("正在启动语音输入...");
            startSogouVoiceInput();
            return;
        }

        try {
            // 创建语音识别器
            mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext);
            mSpeechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    if (DEBUG) {
                        Log.i(TAG, "onReadyForSpeech - 准备就绪");
                    }
                    mIsListening = true;
                    updateStatus("正在聆听...");
                }

                @Override
                public void onBeginningOfSpeech() {
                    if (DEBUG) {
                        Log.d(TAG, "onBeginningOfSpeech - 检测到语音开始");
                    }
                    updateStatus("正在聆听...");
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                    // 音量变化，可以更新波形
                    if (DEBUG) {
                        Log.v(TAG, "onRmsChanged: " + rmsdB);
                    }
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                    if (DEBUG) {
                        Log.d(TAG, "onBufferReceived: " + buffer.length + " bytes");
                    }
                }

                @Override
                public void onEndOfSpeech() {
                    if (DEBUG) {
                        Log.i(TAG, "onEndOfSpeech - 语音结束");
                    }
                    mIsListening = false;
                    updateStatus("正在识别...");
                }

                @Override
                public void onError(int error) {
                    if (DEBUG) {
                        Log.e(TAG, "onError: " + error);
                    }

                    mIsListening = false;

                    String errorMessage;
                    switch (error) {
                        case SpeechRecognizer.ERROR_AUDIO:
                            errorMessage = "音频录制错误";
                            break;
                        case SpeechRecognizer.ERROR_CLIENT:
                            errorMessage = "客户端错误";
                            break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            errorMessage = "权限不足";
                            break;
                        case SpeechRecognizer.ERROR_NETWORK:
                            errorMessage = "网络错误";
                            break;
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            errorMessage = "网络超时";
                            break;
                        case SpeechRecognizer.ERROR_NO_MATCH:
                            errorMessage = "未识别到内容";
                            break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                            errorMessage = "识别服务忙";
                            break;
                        case SpeechRecognizer.ERROR_SERVER:
                            errorMessage = "服务器错误";
                            break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            errorMessage = "语音超时";
                            break;
                        default:
                            errorMessage = "未知错误: " + error;
                    }

                    Log.e(TAG, "语音识别错误: " + errorMessage);

                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        // 这些错误可能是正常的（用户没说话）
                        updateStatus("未识别到语音");
                        scheduleClose();
                    } else {
                        updateStatus(errorMessage);
                        scheduleClose();
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    if (DEBUG) {
                        Log.i(TAG, "========================================");
                        Log.i(TAG, "onResults - 识别结果");
                        Log.i(TAG, "========================================");
                    }

                    mIsListening = false;

                    List<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        mRecognizedText = matches.get(0);
                        if (DEBUG) {
                            Log.i(TAG, "识别结果: " + mRecognizedText);
                        }

                        updateStatus("已识别");
                        updateResult(mRecognizedText);

                        // 发送到 AI Agent
                        sendToAgent(mRecognizedText);
                    } else {
                        if (DEBUG) {
                            Log.w(TAG, "识别结果为空");
                        }
                        updateStatus("未识别到内容");
                        scheduleClose();
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    if (DEBUG) {
                        Log.d(TAG, "onPartialResults");
                    }

                    List<String> matches = partialResults.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String partialText = matches.get(0);
                        if (DEBUG) {
                            Log.d(TAG, "部分结果: " + partialText);
                        }
                        updateResult(partialText);
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                    if (DEBUG) {
                        Log.d(TAG, "onEvent: " + eventType);
                    }
                }
            });

            // 创建识别 Intent
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

            // 开始监听
            mSpeechRecognizer.startListening(intent);

            if (DEBUG) {
                Log.i(TAG, "语音识别已启动");
            }

        } catch (Exception e) {
            Log.e(TAG, "启动语音识别失败", e);
            updateStatus("启动失败: " + e.getMessage());
            scheduleClose();
        }
    }

    /**
     * 启动搜狗输入法语音输入
     */
    private void startSogouVoiceInput() {
        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "启动搜狗输入法语音输入");
            Log.i(TAG, "========================================");
        }

        // 方法1: 尝试调起搜狗 IME 的语音输入
        try {
            Intent intent = new Intent("com.sohu.inputmethod.sogou.action.VOICE_INPUT");
            intent.setPackage(SOGOU_VOICE_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityForResult(intent, REQUEST_SOGOU_VOICE);
            Log.i(TAG, "搜狗语音输入已启动 (方法1)");
            return;
        } catch (Exception e1) {
            Log.w(TAG, "方法1失败: " + e1.getMessage());
        }

        // 方法2: 尝试 ACTION_WEB_SEARCH
        try {
            Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityForResult(intent, REQUEST_SOGOU_VOICE);
            Log.i(TAG, "Web Search 语音已启动");
            return;
        } catch (Exception e2) {
            Log.w(TAG, "方法2失败: " + e2.getMessage());
        }

        // 方法3: 尝试 ACTION_ASSIST
        try {
            Intent intent = new Intent(Intent.ACTION_ASSIST);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityForResult(intent, REQUEST_SOGOU_VOICE);
            Log.i(TAG, "Assist 语音已启动");
            return;
        } catch (Exception e3) {
            Log.w(TAG, "方法3失败: " + e3.getMessage());
        }

        // 全部失败，回退到文本输入模式
        Log.e(TAG, "所有语音输入方式都不可用，回退到文本模式");
        updateStatus("语音不可用，请使用文本输入");
        showTextInputMode();
    }

    /**
     * 回退到文本输入模式
     */
    private void showTextInputMode() {
        if (DEBUG) {
            Log.i(TAG, "显示文本输入界面");
        }

        // 修改 UI 显示文本输入框
        if (mStatusText != null) {
            mStatusText.setText("语音不可用，请输入命令");
            mStatusText.setTextSize(20);
        }

        // 这里可以添加一个输入框，但目前只是显示提示并延迟关闭
        scheduleClose();
    }

    /**
     * Activity 结果回调
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_SOGOU_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                String result = data.getStringExtra("result");
                if (result != null && !result.isEmpty()) {
                    if (DEBUG) {
                        Log.i(TAG, "搜狗语音识别结果: " + result);
                    }
                    mRecognizedText = result;
                    updateStatus("已识别");
                    updateResult(result);
                    sendToAgent(result);
                } else {
                    if (DEBUG) {
                        Log.w(TAG, "搜狗语音识别结果为空");
                    }
                    updateStatus("未识别到语音");
                    scheduleClose();
                }
            } else {
                if (DEBUG) {
                    Log.w(TAG, "搜狗语音输入取消或失败");
                }
                updateStatus("语音输入取消");
                scheduleClose();
            }
        }
    }

    /**
     * 停止语音识别
     */
    private void stopSpeechRecognition() {
        if (DEBUG) {
            Log.i(TAG, "停止语音识别");
        }

        mIsListening = false;

        if (mSpeechRecognizer != null) {
            try {
                mSpeechRecognizer.stopListening();
                mSpeechRecognizer.cancel();
                mSpeechRecognizer.destroy();
            } catch (Exception e) {
                Log.w(TAG, "停止语音识别时出错", e);
            }
            mSpeechRecognizer = null;
        }
    }

    /**
     * 更新状态文本
     */
    private void updateStatus(String text) {
        if (mStatusText != null) {
            mHandler.post(() -> {
                if (mStatusText != null) {
                    mStatusText.setText(text);
                }
            });
        }
    }

    /**
     * 更新结果文本
     */
    private void updateResult(String text) {
        if (mResultText != null) {
            mHandler.post(() -> {
                if (mResultText != null) {
                    mResultText.setText(text);
                }
            });
        }
    }

    /**
     * 更新 AI 响应文本
     */
    private void updateAgentResponse(String text) {
        mAgentResponse = text;
        if (mResultText != null) {
            mHandler.post(() -> {
                if (mResultText != null) {
                    mResultText.setText(text);
                }
            });
        }
    }

    /**
     * 绑定 AI Agent 服务
     */
    private void bindAgentService() {
        if (DEBUG) {
            Log.i(TAG, "绑定 AI Agent 服务...");
        }

        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.server.ai", "agent"));

        try {
            mServiceBound = bindServiceAsUser(intent, mServiceConnection,
                    Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
            if (DEBUG) {
                Log.i(TAG, "bindService 返回: " + mServiceBound);
            }
        } catch (Exception e) {
            Log.e(TAG, "绑定服务失败", e);
        }
    }

    /**
     * 解除服务绑定
     */
    private void unbindAgentService() {
        if (mServiceBound && mAgentService != null) {
            try {
                unbindService(mServiceConnection);
                mServiceBound = false;
                mAgentService = null;
                if (DEBUG) {
                    Log.i(TAG, "已解除服务绑定");
                }
            } catch (Exception e) {
                Log.w(TAG, "解除绑定时出错", e);
            }
        }
    }

    /**
     * 服务连接
     */
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) {
                Log.i(TAG, "========================================");
                Log.i(TAG, "服务已连接: " + name);
                Log.i(TAG, "========================================");
            }

            mAgentService = IAgentService.Stub.asInterface(service);

            // 如果已经有识别结果，发送它
            if (mRecognizedText != null && !mRecognizedText.isEmpty()) {
                sendToAgent(mRecognizedText);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) {
                Log.i(TAG, "服务断开连接: " + name);
            }
            mAgentService = null;
        }
    };

    /**
     * 发送文本到 AI Agent
     */
    private void sendToAgent(String text) {
        if (text == null || text.isEmpty()) {
            if (DEBUG) {
                Log.w(TAG, "发送文本为空");
            }
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "发送任务到 AI Agent");
            Log.i(TAG, "  文本: " + text);
            Log.i(TAG, "========================================");
        }

        if (mAgentService == null) {
            if (DEBUG) {
                Log.w(TAG, "Agent 服务未连接，先等待");
            }
            // 服务可能还没连接，等待一下再试
            mHandler.postDelayed(() -> {
                if (mAgentService != null) {
                    sendToAgent(text);
                } else {
                    Log.e(TAG, "Agent 服务连接超时");
                    updateStatus("服务连接失败");
                    scheduleClose();
                }
            }, 1000);
            return;
        }

        try {
            updateStatus("AI 处理中...");

            mAgentService.sendTask(text, new IAgentServiceCallback.Stub() {
                @Override
                public void onThinking(String thinking) throws RemoteException {
                    if (DEBUG) {
                        Log.d(TAG, "AI 思考: " + thinking);
                    }
                }

                @Override
                public void onText(String text) throws RemoteException {
                    if (DEBUG) {
                        Log.i(TAG, "AI 响应: " + text);
                    }
                    mHandler.post(() -> updateAgentResponse(text));
                }

                @Override
                public void onToolResult(String toolName, Bundle result) throws RemoteException {
                    if (DEBUG) {
                        Log.d(TAG, "工具执行: " + toolName + " -> " + result);
                    }
                }

                @Override
                public void onComplete() throws RemoteException {
                    if (DEBUG) {
                        Log.i(TAG, "========================================");
                        Log.i(TAG, "AI 处理完成");
                        Log.i(TAG, "========================================");
                    }
                    mHandler.post(() -> {
                        updateStatus("处理完成");
                        scheduleClose();
                    });
                }

                @Override
                public void onError(String error) throws RemoteException {
                    Log.e(TAG, "AI 错误: " + error);
                    mHandler.post(() -> {
                        updateStatus("错误: " + error);
                        scheduleClose();
                    });
                }
            });

        } catch (RemoteException e) {
            Log.e(TAG, "调用 Agent 服务失败", e);
            updateStatus("调用失败");
            scheduleClose();
        }
    }

    /**
     * 计划关闭 Activity
     */
    private void scheduleClose() {
        if (DEBUG) {
            Log.i(TAG, "计划关闭 Activity，延迟 " + CLOSE_DELAY_MS + " ms");
        }

        // 移除之前的关闭任务
        if (mCloseRunnable != null) {
            mHandler.removeCallbacks(mCloseRunnable);
        }

        mCloseRunnable = () -> {
            if (DEBUG) {
                Log.i(TAG, "关闭 Activity");
            }
            finish();
        };

        mHandler.postDelayed(mCloseRunnable, CLOSE_DELAY_MS);
    }

    /**
     * 取消关闭计划
     */
    private void cancelClose() {
        if (mCloseRunnable != null) {
            mHandler.removeCallbacks(mCloseRunnable);
            mCloseRunnable = null;
            if (DEBUG) {
                Log.d(TAG, "取消关闭计划");
            }
        }
    }

    @Override
    public void finish() {
        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "VoiceAssistActivity finish");
            Log.i(TAG, "========================================");
        }
        super.finish();
    }
}
