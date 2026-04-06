package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ai.IAgentService;
import android.app.ai.IAgentServiceCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.ai.MiniMaxClient.Callback;
import com.android.server.ai.MiniMaxClient.AgentTool;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AgentService - MiniMax AI助手服务
 *
 * <p>该服务是Android系统级AI助手的核心组件，负责：
 * <ul>
 *   <li>管理与MiniMax大模型的通信</li>
 *   <li>提供屏幕截图和用户界面交互能力</li>
 *   <li>管理用户记忆和偏好设置</li>
 *   <li>调度和执行定时任务</li>
 *   <li>通过Binder接口暴露给客户端应用</li>
 * </ul>
 *
 * <p>服务在系统启动后通过SystemServiceManager发布，通过设置观察者监听系统设置的变更。
 *
 * @see SystemService
 */
public class AgentService extends SystemService {
    private static final String TAG = "AgentService";
    private static final boolean DEBUG = true;

    public static final String SERVICE_NAME = "agent";

    /** APP 调用 AgentService 的 Intent Action */
    public static final String ACTION_AGENT_SERVICE = "android.app.ai.ACTION_AGENT_SERVICE";

    /** Intent Extra: 要执行的任务 */
    public static final String EXTRA_TASK = "android.app.ai.EXTRA_TASK";

    /** Intent Extra: 是否启用服务 */
    public static final String EXTRA_ENABLED = "android.app.ai.EXTRA_ENABLED";

    /** Intent Extra: 点击坐标 X */
    public static final String EXTRA_CLICK_X = "android.app.ai.EXTRA_CLICK_X";

    /** Intent Extra: 点击坐标 Y */
    public static final String EXTRA_CLICK_Y = "android.app.ai.EXTRA_CLICK_Y";

    /** APP 调用结果 Broadcast Action */
    public static final String ACTION_AGENT_RESULT = "android.app.ai.ACTION_AGENT_RESULT";

    /** APP 调用结果 Extra: 状态码 */
    public static final String EXTRA_RESULT_CODE = "android.app.ai.EXTRA_RESULT_CODE";

    /** APP 调用结果 Extra: 结果数据 */
    public static final String EXTRA_RESULT_DATA = "android.app.ai.EXTRA_RESULT_DATA";

    private static final String TAG_AGENT = "MiniMaxAgent";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mEnabled = false;

    @GuardedBy("mLock")
    private String mApiKey;

    private final Context mContext;
    private final Handler mHandler;
    private final Executor mExecutor;

    private final AgentScreenCapture mScreenCapture;
    private final AgentInputController mInputController;
    private final MiniMaxClient mMiniMaxClient;
    private final UserMemoryManager mUserMemory;
    private final TaskScheduler mTaskScheduler;
    private VoiceWakeupManager mVoiceWakeup;
    private VoiceInputManager mVoiceInputManager;

    // ASR 凭证
    private String mAsrAppKey;
    private String mAsrAccessKey;
    private String mAsrResourceId = "volc.seedasr.sauc.duration";

    private final AtomicBoolean mProcessing = new AtomicBoolean(false);

    private SettingsObserver mSettingsObserver;

    // MCP 相关组件
    private McpServerManager mMcpServerManager;
    private final java.util.Map<String, McpToolAdapter> mMcpToolAdapters = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 构造AgentService实例
     *
     * <p>初始化所有子组件：屏幕捕获、输入控制、迷你最大客户端、用户记忆管理器和任务调度器。
     * 然后注册所有可用的AI工具。
     *
     * @param context 系统上下文，用于初始化各子组件
     */
    public AgentService(Context context) {
        super(context);
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = mHandler::post;

        if (DEBUG) {
            Slog.d(TAG, "AgentService constructor starting...");
        }

        mScreenCapture = new AgentScreenCapture(context);
        if (DEBUG) {
            Slog.d(TAG, "AgentScreenCapture initialized");
        }

        mInputController = new AgentInputController(context);
        if (DEBUG) {
            Slog.d(TAG, "AgentInputController initialized");
        }

        mMiniMaxClient = new MiniMaxClient();
        if (DEBUG) {
            Slog.d(TAG, "MiniMaxClient initialized");
        }

        mUserMemory = new UserMemoryManager(context);
        if (DEBUG) {
            Slog.d(TAG, "UserMemoryManager initialized");
        }

        mTaskScheduler = new TaskScheduler(context);
        if (DEBUG) {
            Slog.d(TAG, "TaskScheduler initialized");
        }

        // TODO: 暂时禁用语音唤醒管理器 (唤醒词检测)
        // 使用音量键触发替代唤醒词检测
        // mVoiceWakeup = new VoiceWakeupManager(context);
        // setupVoiceWakeupCallback();

        // 初始化语音输入管理器 (音量键触发)
        mVoiceInputManager = new VoiceInputManager(context);
        setupVoiceInputCallback();
        if (DEBUG) {
            Slog.d(TAG, "VoiceInputManager initialized");
        }

        // 加载 ASR 凭证
        loadAsrCredentials();

        registerTools();
        
        if (DEBUG) {
            Slog.d(TAG, "AgentService constructor completed");
        }
    }

    /**
     * 设置语音唤醒回调
     *
     * <p>配置 VoiceWakeupManager 的监听器，处理以下事件：
     * <ul>
     *   <li>onWakewordDetected - 唤醒词被检测到，开始语音命令接收</li>
     *   <li>onVoiceCommand - 收到语音命令，发送给 AI 处理</li>
     *   <li>onError - 发生错误，记录日志</li>
     * </ul>
     */
    private void setupVoiceWakeupCallback() {
        // TODO: 语音唤醒功能暂时禁用，使用音量键触发替代
        if (DEBUG) {
            Slog.d(TAG, "语音唤醒回调已禁用 (使用音量键替代)");
        }
    }

    /**
     * 设置语音输入回调 (音量键触发)
     *
     * <p>配置 VoiceInputManager 的监听器，处理以下事件：
     * <ul>
     *   <li>onVoiceInputStart - 开始录音</li>
     *   <li>onVoiceInputEnd - 录音结束，开始识别</li>
     *   <li>onTranscript - 收到识别结果，发送给 AI 处理</li>
     *   <li>onError - 发生错误，记录日志</li>
     * </ul>
     */
    private void setupVoiceInputCallback() {
        if (DEBUG) {
            Slog.d(TAG, "设置语音输入回调...");
        }

        mVoiceInputManager.setCallback(new VoiceInputManager.Callback() {
            @Override
            public void onVoiceInputStart() {
                Slog.i(TAG, "语音输入开始录音...");
            }

            @Override
            public void onVoiceInputEnd() {
                Slog.i(TAG, "语音输入录音结束，等待识别...");
            }

            @Override
            public void onTranscript(String text) {
                Slog.i(TAG, "语音识别结果: " + text);
                // 将识别结果作为语音命令发送给 AI 处理
                processVoiceCommand(text);
            }

            @Override
            public void onError(String error) {
                Slog.e(TAG, "语音输入错误: " + error);
            }
        });

        // 启动语音输入管理
        mVoiceInputManager.start();

        if (DEBUG) {
            Slog.d(TAG, "语音输入回调设置完成");
        }
    }

    /**
     * 加载 ASR 凭证
     *
     * <p>从 Settings.Global 读取字节跳动 ASR 的 AppKey 和 AccessKey。
     */
    private void loadAsrCredentials() {
        ContentResolver resolver = mContext.getContentResolver();

        mAsrAppKey = Settings.Global.getString(resolver,
                Settings.Global.ASR_APP_ID);
        mAsrAccessKey = Settings.Global.getString(resolver,
                Settings.Global.ASR_ACCESS_KEY);
        String resourceId = Settings.Global.getString(resolver,
                Settings.Global.ASR_RESOURCE_ID);

        if (resourceId != null && !resourceId.isEmpty()) {
            mAsrResourceId = resourceId;
        }

        if (mAsrAppKey != null && !mAsrAppKey.isEmpty()
                && mAsrAccessKey != null && !mAsrAccessKey.isEmpty()) {
            mVoiceInputManager.setCredentials(mAsrAppKey, mAsrAccessKey);
            if (DEBUG) {
                Slog.d(TAG, "ASR 凭证已加载");
            }
        } else {
            Slog.w(TAG, "ASR 凭证未设置，请配置 ASR_APP_ID 和 ASR_ACCESS_KEY");
        }

        if (DEBUG) {
            Slog.d(TAG, "ASR ResourceId: " + mAsrResourceId);
        }
    }

    /**
     * 设置 ASR 凭证
     *
     * @param appKey App Key
     * @param accessKey Access Key
     */
    public void setAsrCredentials(String appKey, String accessKey) {
        mAsrAppKey = appKey;
        mAsrAccessKey = accessKey;

        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.ASR_APP_ID, appKey);
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.ASR_ACCESS_KEY, accessKey);

        if (mVoiceInputManager != null) {
            mVoiceInputManager.setCredentials(appKey, accessKey);
        }

        Slog.i(TAG, "ASR 凭证已更新");
    }

    /**
     * 获取 VoiceInputManager 实例
     *
     * <p>用于在 PhoneWindowManager 中处理音量键事件。
     *
     * @return VoiceInputManager 实例
     */
    public VoiceInputManager getVoiceInputManager() {
        return mVoiceInputManager;
    }

    /**
     * 处理语音命令
     *
     * <p>当收到语音命令时调用此方法。
     * 将命令作为任务发送给 MiniMax AI 进行处理。
     *
     * @param command 语音转写后的文本命令
     */
    private void processVoiceCommand(String command) {
        if (DEBUG) {
            Slog.d(TAG, "处理语音命令: " + command);
        }

        if (command == null || command.trim().isEmpty()) {
            if (DEBUG) {
                Slog.w(TAG, "语音命令为空，忽略");
            }
            return;
        }

        // 保存到对话历史
        mUserMemory.addConversationTurn("user", command);

        // 异步处理命令
        mExecutor.execute(() -> {
            try {
                byte[] screenshot = mScreenCapture.takeScreenshotAsPng();

                if (DEBUG) {
                    Slog.d(TAG, "截图完成，开始 AI 处理...");
                }

                mMiniMaxClient.sendMessage(command, screenshot, new Callback() {
                    @Override
                    public void onThinking(String thinking) {
                        if (DEBUG) {
                            Slog.d(TAG, "语音命令 AI 思考: " + thinking);
                        }
                    }

                    @Override
                    public void onText(String text) {
                        if (DEBUG) {
                            Slog.d(TAG, "语音命令 AI 响应: " + text);
                        }
                        mUserMemory.addConversationTurn("assistant", text);
                    }

                    @Override
                    public void onToolCall(String toolName, Bundle arguments) {
                        if (DEBUG) {
                            Slog.d(TAG, "语音命令工具调用: " + toolName);
                        }
                        try {
                            AgentTool tool = findTool(toolName);
                            if (tool != null) {
                                Bundle result = tool.execute(arguments);
                                if (DEBUG) {
                                    Slog.d(TAG, "工具执行结果: " + toolName + " -> " + result.getString("status"));
                                }
                            } else {
                                Slog.w(TAG, "工具未找到: " + toolName);
                            }
                        } catch (Exception e) {
                            Slog.e(TAG, "工具执行失败: " + toolName, e);
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (DEBUG) {
                            Slog.d(TAG, "语音命令处理完成");
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Slog.e(TAG, "语音命令处理错误: " + error);
                    }
                });
            } catch (Exception e) {
                Slog.e(TAG, "处理语音命令异常", e);
            }
        });
    }

    /**
     * 注册所有AI工具到MiniMaxClient
     *
     * <p>该方法创建并注册以下工具：
     * <ul>
     *   <li>截图工具 - 获取当前屏幕截图</li>
     *   <li>点击工具 - 在指定坐标执行点击操作</li>
     *   <li>滑动工具 - 执行滑动/手势操作</li>
     *   <li>文本输入工具 - 向当前焦点输入文本</li>
     *   <li>启动应用工具 - 根据包名启动应用</li>
     *   <li>返回/主页按键工具 - 模拟系统按键</li>
     *   <li>记忆工具 - 存储和检索用户记忆</li>
     *   <li>偏好设置工具 - 读取/写入系统偏好设置</li>
     *   <li>任务调度工具 - 创建/取消/查询定时任务</li>
     *   <li>上下文工具 - 获取当前屏幕和动作历史</li>
     * </ul>
     *
     * <p>同时设置任务执行器和任务变更监听器，用于响应调度任务的触发。
     */
    private void registerTools() {
        if (DEBUG) {
            Slog.d(TAG, "Registering tools...");
        }

        AgentTools.MemoryManager memoryManager = new AgentTools.MemoryManager() {
            @Override
            public void remember(String key, String value, String category) {
                mUserMemory.remember(key, value, category);
            }

            @Override
            public void recall(String key, String category, UserMemoryManager.RecallCallback callback) {
                mUserMemory.recall(key, category, callback);
            }

            @Override
            public void setPreference(String category, String key, String value) {
                mUserMemory.setPreference(category, key, value);
            }

            @Override
            public void getPreference(String category, String key, UserMemoryManager.RecallCallback callback) {
                mUserMemory.getPreference(category, key, callback);
            }

            @Override
            public void getScreenHistory(UserMemoryManager.ScreenHistoryCallback callback) {
                mUserMemory.getScreenHistory(callback);
            }

            @Override
            public void getActionHistory(UserMemoryManager.ActionHistoryCallback callback) {
                mUserMemory.getActionHistory(callback);
            }

            @Override
            public void getConversationHistory(UserMemoryManager.ConversationHistoryCallback callback) {
                mUserMemory.getConversationHistory(callback);
            }

            @Override
            public void addScreenHistory(String description, byte[] screenshotHash) {
                mUserMemory.addScreenHistory(description, screenshotHash);
            }

            @Override
            public void addActionHistory(String action, String target, String result) {
                mUserMemory.addActionHistory(action, target, result);
            }

            @Override
            public void addConversationTurn(String role, String content) {
                mUserMemory.addConversationTurn(role, content);
            }
        };

        AgentTools.TaskSchedulerInterface schedulerInterface = new AgentTools.TaskSchedulerInterface() {
            @Override
            public String scheduleTask(String description, long triggerAtMillis) {
                return mTaskScheduler.scheduleTask(description, triggerAtMillis);
            }

            @Override
            public String scheduleRecurringTask(String description, long intervalMillis) {
                return mTaskScheduler.scheduleRecurringTask(description, intervalMillis);
            }

            @Override
            public boolean cancelTask(String taskId) {
                return mTaskScheduler.cancelTask(taskId);
            }

            @Override
            public int getPendingTaskCount() {
                return mTaskScheduler.getPendingTaskCount();
            }
        };

        mMiniMaxClient.registerTool(new AgentTools.ScreenshotTool(mScreenCapture::takeScreenshot, memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: screenshot tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.ClickTool(mInputController, memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: click tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.SwipeTool(mInputController, memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: swipe tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.InputTextTool(mInputController, memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: input_text tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.LaunchAppTool(mInputController, memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: launch_app tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.PressBackTool(mInputController, memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: press_back tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.PressHomeTool(mInputController, memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: press_home tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.RememberTool(memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: remember tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.RecallTool(memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: recall tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.SetPreferenceTool(memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: set_preference tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.GetPreferenceTool(memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: get_preference tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.ScheduleTaskTool(schedulerInterface));
        if (DEBUG) {
            Slog.d(TAG, "Registered: schedule_task tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.ScheduleRecurringTool(schedulerInterface));
        if (DEBUG) {
            Slog.d(TAG, "Registered: schedule_recurring tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.CancelTaskTool(schedulerInterface));
        if (DEBUG) {
            Slog.d(TAG, "Registered: cancel_task tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.GetPendingTasksTool(schedulerInterface));
        if (DEBUG) {
            Slog.d(TAG, "Registered: get_pending_tasks tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.GetContextTool(memoryManager));
        if (DEBUG) {
            Slog.d(TAG, "Registered: get_context tool");
        }

        mTaskScheduler.setTaskExecutor((taskId, taskDescription, callback) -> {
            if (DEBUG) {
                Slog.d(TAG, "Executing scheduled task: " + taskId + ", desc=" + taskDescription);
            }
            executeScheduledTask(taskId, taskDescription, callback);
        });

        mTaskScheduler.setTaskChangeListener(new TaskScheduler.TaskChangeListener() {
            @Override
            public void onTaskScheduled(TaskScheduler.ScheduledTask task) {
                if (DEBUG) {
                    Slog.d(TAG, "Task scheduled: " + task.taskId);
                }
            }

            @Override
            public void onTaskStarted(TaskScheduler.ScheduledTask task) {
                if (DEBUG) {
                    Slog.d(TAG, "Task started: " + task.taskId);
                }
            }

            @Override
            public void onTaskCompleted(TaskScheduler.ScheduledTask task, String result) {
                if (DEBUG) {
                    Slog.d(TAG, "Task completed: " + task.taskId + ", result=" + result);
                }
            }

            @Override
            public void onTaskFailed(TaskScheduler.ScheduledTask task, String error) {
                Slog.e(TAG, "Task failed: " + task.taskId + ", error=" + error);
            }

            @Override
            public void onTaskCancelled(TaskScheduler.ScheduledTask task) {
                if (DEBUG) {
                    Slog.d(TAG, "Task cancelled: " + task.taskId);
                }
            }
        });

        if (DEBUG) {
            Slog.d(TAG, "All tools registered successfully");
        }
    }

    /**
     * 执行指定的定时任务
     *
     * <p>当TaskScheduler触发定时任务时调用此方法。它首先截取当前屏幕截图，
     * 然后将任务描述和截图发送给MiniMax大模型进行分析和执行。
     *
     * @param taskId 任务唯一标识符
     * @param taskDescription 任务描述，说明需要执行的操作
     * @param callback 任务执行完成后的回调接口，用于通知执行结果
     */
    private void executeScheduledTask(String taskId, String taskDescription, TaskScheduler.TaskCallback callback) {
        mExecutor.execute(() -> {
            try {
                byte[] screenshot = mScreenCapture.takeScreenshotAsPng();
                
                String prompt = "Execute the following scheduled task: " + taskDescription + 
                        "\n\nTake a screenshot first to understand the current state, " +
                        "then perform the necessary actions to complete this task.";
                
                mMiniMaxClient.sendMessage(prompt, screenshot, new Callback() {
                    @Override
                    public void onThinking(String thinking) {
                        if (DEBUG) {
                            Slog.d(TAG, "Task thinking: " + thinking);
                        }
                    }

                    @Override
                    public void onText(String text) {
                        if (DEBUG) {
                            Slog.d(TAG, "Task text: " + text);
                        }
                    }

                    @Override
                    public void onToolCall(String toolName, Bundle arguments) {
                        if (DEBUG) {
                            Slog.d(TAG, "Task tool call: " + toolName);
                        }
                        AgentTool tool = findTool(toolName);
                        if (tool != null) {
                            try {
                                Bundle result = tool.execute(arguments);
                                if (DEBUG) {
                                    Slog.d(TAG, "Tool result: " + toolName + " -> " + result.getString("status"));
                                }
                            } catch (Exception e) {
                                Slog.e(TAG, "Tool execution failed: " + toolName, e);
                            }
                        }
                    }

                    @Override
                    public void onComplete() {
                        callback.onSuccess("Task completed successfully");
                    }

                    @Override
                    public void onError(String error) {
                        callback.onFailure(error);
                    }
                });
            } catch (Exception e) {
                Slog.e(TAG, "Failed to execute scheduled task: " + taskId, e);
                callback.onFailure(e.getMessage());
            }
        });
    }

    /**
     * 系统服务启动时调用
     *
     * <p>将AgentService的Binder服务发布到系统服务管理器，使其可以被客户端应用访问。
     * 服务名称为"agent"，客户端可通过IAgentService接口与其通信。
     *
     * @see #mBinder
     */
    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.d(TAG, "onStart called");
        }

        publishBinderService(SERVICE_NAME, mBinder);

        // 注册 APP Intent 接收器
        IntentFilter filter = new IntentFilter(ACTION_AGENT_SERVICE);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            mContext.registerReceiver(mAgentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            if (DEBUG) {
                Slog.d(TAG, "Agent Intent Receiver registered");
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to register Agent Intent Receiver", e);
        }

        if (DEBUG) {
            Slog.d(TAG, "AgentService published with name: " + SERVICE_NAME);
        }
    }

    /** 处理来自 APP 的 Intent 请求 */
    private final BroadcastReceiver mAgentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DEBUG) {
                Slog.d(TAG, "Received broadcast: " + action);
            }

            if (ACTION_AGENT_SERVICE.equals(action)) {
                handleAgentIntent(intent);
            }
        }
    };

    /** 处理 Agent Intent */
    private void handleAgentIntent(Intent intent) {
        String task = intent.getStringExtra(EXTRA_TASK);
        Boolean enabled = intent.hasExtra(EXTRA_ENABLED)
                ? intent.getBooleanExtra(EXTRA_ENABLED, false) : null;
        int clickX = intent.getIntExtra(EXTRA_CLICK_X, -1);
        int clickY = intent.getIntExtra(EXTRA_CLICK_Y, -1);

        if (enabled != null) {
            setEnabled(enabled);
            if (DEBUG) {
                Slog.d(TAG, "Enabled set via Intent: " + enabled);
            }
        }

        if (task != null && !task.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "Task received via Intent: " + task);
            }
            sendTask(task, new IAgentServiceCallback.Stub() {
                @Override
                public void onThinking(String thinking) throws RemoteException {
                    if (DEBUG) {
                        Slog.d(TAG, "Thinking: " + thinking);
                    }
                    sendResultBroadcast(thinking, "thinking");
                }

                @Override
                public void onText(String text) throws RemoteException {
                    if (DEBUG) {
                        Slog.d(TAG, "Text: " + text);
                    }
                    sendResultBroadcast(text, "text");
                }

                @Override
                public void onToolResult(String toolName, Bundle result) throws RemoteException {
                    if (DEBUG) {
                        Slog.d(TAG, "Tool result: " + toolName);
                    }
                    sendResultBroadcast(toolName + ":" + result, "tool");
                }

                @Override
                public void onComplete() throws RemoteException {
                    if (DEBUG) {
                        Slog.d(TAG, "Complete");
                    }
                    sendResultBroadcast("complete", "complete");
                }

                @Override
                public void onError(String error) throws RemoteException {
                    if (DEBUG) {
                        Slog.e(TAG, "Error: " + error);
                    }
                    sendResultBroadcast(error, "error");
                }
            });
        }

        if (clickX >= 0 && clickY >= 0) {
            boolean result = click(clickX, clickY);
            if (DEBUG) {
                Slog.d(TAG, "Click at (" + clickX + ", " + clickY + ") result: " + result);
            }
            sendResultBroadcast(String.valueOf(result), "click");
        }
    }

    /** 发送结果 Broadcast 到 APP */
    private void sendResultBroadcast(String data, String type) {
        Intent resultIntent = new Intent(ACTION_AGENT_RESULT);
        resultIntent.putExtra(EXTRA_RESULT_CODE, type);
        resultIntent.putExtra(EXTRA_RESULT_DATA, data);
        try {
            mContext.sendBroadcast(resultIntent);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to send result broadcast", e);
        }
    }

    /**
     * 系统启动阶段回调
     *
     * <p>在不同启动阶段被调用。当系统服务就绪(PHASE_SYSTEM_SERVICES_READY)时，
     * 初始化设置观察者并加载保存的设置。
     *
     * @param phase 当前启动阶段编号
     * @see #PHASE_SYSTEM_SERVICES_READY
     * @see SettingsObserver
     */
    @Override
    public void onBootPhase(int phase) {
        if (DEBUG) {
            Slog.d(TAG, "onBootPhase: " + phase);
        }
        
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            if (DEBUG) {
                Slog.d(TAG, "PHASE_SYSTEM_SERVICES_READY - initializing settings observer");
            }

            mSettingsObserver = new SettingsObserver(mHandler);
            mSettingsObserver.register();
            loadSettings();

            // 初始化 MCP
            initMcp();

            // 如果服务启用，启动语音唤醒监听
            if (mEnabled) {
                if (DEBUG) {
                    Slog.d(TAG, "Agent服务已启用，启动语音唤醒监听");
                }
                startVoiceWakeupIfEnabled();
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Agent服务未启用，跳过语音唤醒监听");
                }
            }

            if (DEBUG) {
                Slog.d(TAG, "Settings loaded, AgentService ready");
            }
        }
    }

    /**
     * 从系统设置加载Agent服务的配置
     *
     * <p>从Settings.Global读取服务启用状态和API密钥，并同步到MiniMaxClient。
     * 此方法在服务初始化和设置变更时调用。
     *
     * @see Settings.Global#AGENT_SERVICE_ENABLED
     * @see Settings.Global#MINIMAX_API_KEY
     */
    private void loadSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        synchronized (mLock) {
            mEnabled = Settings.Global.getInt(resolver,
                    Settings.Global.AGENT_SERVICE_ENABLED, 0) == 1;
            mApiKey = Settings.Global.getString(resolver,
                    Settings.Global.MINIMAX_API_KEY);
            mMiniMaxClient.setApiKey(mApiKey);
        }

        if (DEBUG) {
            Slog.d(TAG, "loadSettings - enabled: " + mEnabled + ", apiKey configured: " + (mApiKey != null && !mApiKey.isEmpty()));
        }
    }

    /**
     * 如果语音唤醒启用，则启动监听
     *
     * <p>检查语音唤醒功能是否启用，如果启用则启动语音监听。
     * 此方法在服务启用时和系统启动完成后调用。
     */
    private void startVoiceWakeupIfEnabled() {
        if (mVoiceWakeup == null) {
            if (DEBUG) {
                Slog.w(TAG, "VoiceWakeupManager 未初始化");
            }
            return;
        }

        // 检查语音唤醒是否启用
        if (!mVoiceWakeup.isEnabled()) {
            if (DEBUG) {
                Slog.d(TAG, "语音唤醒未启用，不启动监听");
            }
            return;
        }

        if (DEBUG) {
            Slog.i(TAG, "启动语音唤醒监听...");
            Slog.d(TAG, "  - 热词: " + mVoiceWakeup.getHotword());
            Slog.d(TAG, "  - 监听器: " + (mVoiceWakeup.getListener() != null ? "已设置" : "未设置"));
        }

        mVoiceWakeup.startListening();

        if (DEBUG) {
            Slog.i(TAG, "语音唤醒监听已启动");
        }
    }

    /**
     * 停止语音唤醒监听
     *
     * <p>停止当前正在进行的语音唤醒监听。
     */
    private void stopVoiceWakeup() {
        if (mVoiceWakeup == null) {
            if (DEBUG) {
                Slog.w(TAG, "VoiceWakeupManager 未初始化");
            }
            return;
        }

        if (DEBUG) {
            Slog.i(TAG, "停止语音唤醒监听...");
        }

        mVoiceWakeup.stopListening();

        if (DEBUG) {
            Slog.i(TAG, "语音唤醒监听已停止");
        }
    }

    /**
     * 获取语音唤醒管理器
     *
     * <p>供外部访问语音唤醒功能。
     *
     * @return VoiceWakeupManager 实例，可能为 null
     */
    public VoiceWakeupManager getVoiceWakeupManager() {
        return mVoiceWakeup;
    }

    /**
     * 初始化 MCP (Model Context Protocol) 支持
     *
     * <p>启动 Python MCP Server 并将其工具注册到 MiniMaxClient。
     * MCP Server 通过 TCP Socket 与 Android 通信，实现工具的动态加载。
     *
     * <p>该方法在系统服务就绪后调用，执行以下操作：
     * <ul>
     *   <li>创建 McpServerManager 管理 MCP Server 生命周期</li>
     *   <li>启动 Python MCP Server 进程</li>
     *   <li>连接并从 Server 获取可用工具列表</li>
     *   <li>将 MCP 工具适配并注册到 MiniMaxClient</li>
     * </ul>
     */
    private void initMcp() {
        if (DEBUG) {
            Slog.d(TAG, "Initializing MCP support...");
        }

        try {
            // 创建 MCP Server Manager
            mMcpServerManager = new McpServerManager(mContext);

            // 设置回调
            mMcpServerManager.setCallback(new McpServerManager.McpCallback() {
                @Override
                public void onServerStarted(int port) {
                    Slog.i(TAG, "MCP Server started on port " + port);
                }

                @Override
                public void onServerStopped() {
                    Slog.i(TAG, "MCP Server stopped");
                }

                @Override
                public void onServerCrashed(String reason) {
                    Slog.w(TAG, "MCP Server crashed: " + reason);
                }

                @Override
                public void onToolsReceived(McpServerManager.ServerConfig config) {
                    Slog.i(TAG, "Received tools from MCP Server: " + config.id);
                    // MCP Client 已自动获取工具列表
                }

                @Override
                public void onError(String error) {
                    Slog.e(TAG, "MCP error: " + error);
                }
            });

            // 尝试启动默认 MCP Server
            boolean started = mMcpServerManager.startDefaultServer();
            if (started) {
                Slog.i(TAG, "MCP Server started successfully");

                // 注册 MCP 工具（如果有的话）
                registerMcpTools();
            } else {
                Slog.w(TAG, "Failed to start MCP Server, MCP tools will not be available");
            }

        } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize MCP", e);
        }
    }

    /**
     * 注册 MCP 工具到 MiniMaxClient
     *
     * <p>从已连接的 MCP Server 获取工具列表，
     * 为每个工具创建 McpToolAdapter 并注册到 MiniMaxClient。
     */
    private void registerMcpTools() {
        if (mMcpServerManager == null) {
            return;
        }

        McpClient mcpClient = mMcpServerManager.getDefaultMcpClient();
        if (mcpClient == null || !mcpClient.isConnected()) {
            if (DEBUG) {
                Slog.d(TAG, "MCP Client not connected yet");
            }
            return;
        }

        // 异步获取工具列表
        mcpClient.listToolsAsync(new McpClient.McpCallback() {
            @Override
            public void onToolsList(java.util.List<McpTool> tools) {
                Slog.i(TAG, "Registering " + tools.size() + " MCP tools");
                for (McpTool tool : tools) {
                    if (!mMcpToolAdapters.containsKey(tool.name)) {
                        McpToolAdapter adapter = new McpToolAdapter(tool, mcpClient);
                        mMcpToolAdapters.put(tool.name, adapter);
                        mMiniMaxClient.registerTool(adapter);
                        Slog.i(TAG, "Registered MCP tool: " + tool.name);
                    }
                }
            }

            @Override
            public void onError(String error) {
                Slog.e(TAG, "Failed to get MCP tools: " + error);
            }
        });
    }

    /**
     * 关闭 MCP 支持
     *
     * <p>停止 MCP Server 并清理相关资源。
     * 在服务停止时调用。
     */
    private void shutdownMcp() {
        if (DEBUG) {
            Slog.d(TAG, "Shutting down MCP support...");
        }

        if (mMcpServerManager != null) {
            // 注销所有 MCP 工具
            for (String toolName : mMcpToolAdapters.keySet()) {
                mMiniMaxClient.unregisterTool(toolName);
            }
            mMcpToolAdapters.clear();

            // 停止 MCP Server
            mMcpServerManager.stopAllServers();
            mMcpServerManager = null;
        }

        if (DEBUG) {
            Slog.d(TAG, "MCP support shut down");
        }
    }

    private final IAgentService.Stub mBinder = new AgentServiceStub();

    /**
     * AgentService的Binder.stub实现
     *
     * <p>通过IAgentService接口向客户端应用暴露服务功能。
     * 每个Binder方法都转发到AgentService对应的公有方法进行处理。
     *
     * @see IAgentService
     */
    private final class AgentServiceStub extends IAgentService.Stub {
        private static final String PERMISSION = "android.permission.ACCESS_AGENT_SERVICE";

        private void enforceAccessPermission() {
            getContext().enforceCallingPermission(PERMISSION, "Access to AgentService denied");
        }

        @Override
        public void sendTask(String task, IAgentServiceCallback callback) {
            enforceAccessPermission();
            AgentService.this.sendTask(task, callback);
        }

        @Override
        public boolean isEnabled() {
            enforceAccessPermission();
            return AgentService.this.isEnabled();
        }

        @Override
        public void setEnabled(boolean enabled) {
            enforceAccessPermission();
            AgentService.this.setEnabled(enabled);
        }

        @Override
        public void setApiKey(String apiKey) {
            enforceAccessPermission();
            AgentService.this.setApiKey(apiKey);
        }

        @Override
        public String getApiKey() {
            enforceAccessPermission();
            return AgentService.this.getApiKey();
        }

        @Override
        public Bitmap takeScreenshot() {
            enforceAccessPermission();
            return AgentService.this.takeScreenshot();
        }

        @Override
        public boolean click(int x, int y) {
            enforceAccessPermission();
            return AgentService.this.click(x, y);
        }

        @Override
        public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
            enforceAccessPermission();
            return AgentService.this.swipe(x1, y1, x2, y2, duration);
        }

        @Override
        public boolean inputText(String text) {
            enforceAccessPermission();
            return AgentService.this.inputText(text);
        }

        @Override
        public boolean pressBack() {
            enforceAccessPermission();
            return AgentService.this.pressBack();
        }

        @Override
        public boolean pressHome() {
            enforceAccessPermission();
            return AgentService.this.pressHome();
        }

        @Override
        public boolean launchApp(String packageName) {
            enforceAccessPermission();
            return AgentService.this.launchApp(packageName);
        }
    }

    /**
     * 处理用户任务请求
     *
     * <p>验证任务输入、服务启用状态和API密钥配置后，
     * 将任务连同屏幕截图一起发送给MiniMax大模型处理。
     * 处理过程中的思考、文本响应、工具调用和最终结果都通过callback回调。
     *
     * <p>任务处理是异步的，通过Executor在后台线程执行。
     * 同一时刻只能处理一个任务，新的任务请求会在服务忙时被拒绝。
     *
     * @param task 用户输入的任务描述
     * @param callback 用于接收处理进度和结果的回调接口
     */
    private void sendTask(String task, IAgentServiceCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "sendTask received: " + task);
        }

        if (task == null || task.trim().isEmpty()) {
            notifyError(callback, "Task cannot be empty");
            return;
        }

        boolean enabled;
        String apiKey;
        synchronized (mLock) {
            enabled = mEnabled;
            apiKey = mApiKey;
        }

        if (!enabled) {
            if (DEBUG) {
                Slog.w(TAG, "Agent service is not enabled");
            }
            notifyError(callback, "Agent service is not enabled");
            return;
        }

        if (apiKey == null || apiKey.isEmpty()) {
            if (DEBUG) {
                Slog.w(TAG, "API key not configured");
            }
            notifyError(callback, "API key not configured");
            return;
        }

        if (!mProcessing.compareAndSet(false, true)) {
            if (DEBUG) {
                Slog.w(TAG, "Agent is already processing a task");
            }
            notifyError(callback, "Agent is already processing a task");
            return;
        }

        mUserMemory.addConversationTurn("user", task);

        final IAgentServiceCallback finalCallback = callback;
        mExecutor.execute(() -> {
            if (DEBUG) {
                Slog.d(TAG, "Processing task...");
            }
            
            try {
                byte[] screenshot = mScreenCapture.takeScreenshotAsPng();

                if (DEBUG) {
                    Slog.d(TAG, "Screenshot taken, sending to MiniMax...");
                }

                mMiniMaxClient.sendMessage(task, screenshot, new Callback() {
                    @Override
                    public void onThinking(String thinking) {
                        if (DEBUG) {
                            Slog.d(TAG, "Thinking: " + thinking);
                        }
                        notifyThinking(finalCallback, thinking);
                    }

                    @Override
                    public void onText(String text) {
                        if (DEBUG) {
                            Slog.d(TAG, "Text: " + text);
                        }
                        mUserMemory.addConversationTurn("assistant", text);
                        notifyText(finalCallback, text);
                    }

                    @Override
                    public void onToolCall(String toolName, Bundle arguments) {
                        if (DEBUG) {
                            Slog.d(TAG, "Tool call: " + toolName + " with args: " + arguments);
                        }
                        try {
                            AgentTool tool = findTool(toolName);
                            Bundle result;
                            if (tool != null) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Executing tool: " + toolName);
                                }
                                result = tool.execute(arguments);
                                if (DEBUG) {
                                    Slog.d(TAG, "Tool result: " + toolName + " -> " + result.getString("status"));
                                }
                            } else {
                                result = new Bundle();
                                result.putString("status", "error");
                                result.putString("message", "Tool not found: " + toolName);
                                Slog.e(TAG, "Tool not found: " + toolName);
                            }
                            notifyToolResult(finalCallback, toolName, result);
                        } catch (Exception e) {
                            Slog.e(TAG, "Tool execution failed: " + toolName, e);
                            Bundle result = new Bundle();
                            result.putString("status", "error");
                            result.putString("message", e.getMessage());
                            notifyToolResult(finalCallback, toolName, result);
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (DEBUG) {
                            Slog.d(TAG, "Task complete");
                        }
                        notifyComplete(finalCallback);
                        mProcessing.set(false);
                    }

                    @Override
                    public void onError(String error) {
                        Slog.e(TAG, "Task error: " + error);
                        notifyError(finalCallback, error);
                        mProcessing.set(false);
                    }
                });
            } catch (Exception e) {
                Slog.e(TAG, "Error processing task", e);
                notifyError(finalCallback, e.getMessage());
                mProcessing.set(false);
            }
        });
    }

    /**
     * 向客户端发送思考过程回调
     *
     * <p>当MiniMax大模型产生思考过程时调用。如果回调为null或远程调用失败，忽略该错误。
     *
     * @param callback 客户端回调接口
     * @param thinking 大模型的思考内容
     */
    private void notifyThinking(@Nullable IAgentServiceCallback callback, @NonNull String thinking) {
        if (callback == null) {
            return;
        }
        try {
            callback.onThinking(thinking);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver thinking callback", e);
        }
    }

    /**
     * 向客户端发送文本响应回调
     *
     * <p>当MiniMax大模型产生文本输出时调用。文本会被保存到用户记忆中的对话历史。
     *
     * @param callback 客户端回调接口
     * @param text 大模型的文本响应
     */
    private void notifyText(@Nullable IAgentServiceCallback callback, @NonNull String text) {
        if (callback == null) {
            return;
        }
        try {
            callback.onText(text);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver text callback", e);
        }
    }

    /**
     * 向客户端发送工具执行结果回调
     *
     * <p>当AI工具执行完成后调用此方法将结果返回给客户端。
     *
     * @param callback 客户端回调接口
     * @param toolName 被执行的工具名称
     * @param result 工具执行结果，包含status和message字段
     */
    private void notifyToolResult(@Nullable IAgentServiceCallback callback, @NonNull String toolName,
            @NonNull Bundle result) {
        if (callback == null) {
            return;
        }
        try {
            callback.onToolResult(toolName, result);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver tool result callback", e);
        }
    }

    /**
     * 向客户端发送任务完成回调
     *
     * <p>当整个任务处理流程成功完成时调用此方法通知客户端。
     *
     * @param callback 客户端回调接口
     */
    private void notifyComplete(@Nullable IAgentServiceCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onComplete();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver completion callback", e);
        }
    }

    /**
     * 向客户端发送错误回调
     *
     * <p>当任务处理过程中发生错误时调用此方法通知客户端。
     *
     * @param callback 客户端回调接口
     * @param error 错误信息描述
     */
    private void notifyError(@Nullable IAgentServiceCallback callback, @Nullable String error) {
        if (callback == null) {
            return;
        }
        try {
            callback.onError(error != null ? error : "Unknown error");
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver error callback", e);
        }
    }

    /**
     * 通过反射查找指定名称的AI工具
     *
     * <p>通过Java反射访问MiniMaxClient的私有mTools映射表，
     * 根据工具名称查找对应的AgentTool实例。
     *
     * @param name 工具名称
     * @return 找到的工具实例，如果不存在或反射失败则返回null
     */
    @Nullable
    private AgentTool findTool(String name) {
        try {
            java.lang.reflect.Field field = MiniMaxClient.class.getDeclaredField("mTools");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, AgentTool> tools = (java.util.Map<String, AgentTool>) field.get(mMiniMaxClient);
            return tools.get(name);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to find tool: " + name, e);
            return null;
        }
    }

    /**
     * 查询Agent服务是否已启用
     *
     * @return true表示服务已启用，false表示禁用
     */
    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    /**
     * 设置Agent服务启用/禁用状态
     *
     * <p>修改服务状态并持久化到系统设置。状态变更后：
     * <ul>
     *   <li>如果启用，启动语音唤醒监听</li>
     *   <li>如果禁用，停止语音唤醒监听</li>
     * </ul>
     *
     * <p>SettingsObserver会检测到变化并重新加载配置。
     *
     * @param enabled true启用服务，false禁用服务
     */
    public void setEnabled(boolean enabled) {
        boolean oldEnabled;
        synchronized (mLock) {
            oldEnabled = mEnabled;
            mEnabled = enabled;
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AGENT_SERVICE_ENABLED, enabled ? 1 : 0);
            if (DEBUG) {
                Slog.i(TAG, "setEnabled: " + oldEnabled + " -> " + enabled);
            }
        }

        // 在锁外处理语音监听，因为可能涉及阻塞操作
        if (enabled && !oldEnabled) {
            // 从禁用变为启用
            Slog.i(TAG, "Agent服务已启用，启动语音唤醒监听");
            startVoiceWakeupIfEnabled();
        } else if (!enabled && oldEnabled) {
            // 从启用变为禁用
            Slog.i(TAG, "Agent服务已禁用，停止语音唤醒监听");
            stopVoiceWakeup();
        }
    }

    /**
     * 设置MiniMax API密钥
     *
     * <p>同时更新内存中的API密钥和系统设置持久化存储。
     *
     * @param apiKey MiniMax服务的API密钥
     */
    public void setApiKey(String apiKey) {
        synchronized (mLock) {
            mApiKey = apiKey;
            mMiniMaxClient.setApiKey(apiKey);
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.MINIMAX_API_KEY, apiKey);
            if (DEBUG) {
                Slog.d(TAG, "setApiKey: " + (apiKey != null ? "configured" : "null"));
            }
        }
    }

    /**
     * 获取当前配置的MiniMax API密钥
     *
     * @return API密钥字符串，如果未设置则返回null
     */
    public String getApiKey() {
        synchronized (mLock) {
            return mApiKey;
        }
    }

    /**
     * 截取当前屏幕截图
     *
     * @return 当前屏幕的Bitmap图像
     */
    public Bitmap takeScreenshot() {
        return mScreenCapture.takeScreenshot();
    }

    /**
     * 在指定坐标执行点击操作
     *
     * @param x 点击的X坐标
     * @param y 点击的Y坐标
     * @return true表示执行成功，false表示失败
     */
    public boolean click(int x, int y) {
        return mInputController.click(x, y);
    }

    /**
     * 执行滑动/手势操作
     *
     * @param x1 起始点X坐标
     * @param y1 起始点Y坐标
     * @param x2 结束点X坐标
     * @param y2 结束点Y坐标
     * @param duration 滑动持续时间（毫秒）
     * @return true表示执行成功，false表示失败
     */
    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        return mInputController.swipe(x1, y1, x2, y2, duration);
    }

    /**
     * 向当前焦点控件输入文本
     *
     * @param text 要输入的文本内容
     * @return true表示执行成功，false表示失败
     */
    public boolean inputText(String text) {
        return mInputController.inputText(text);
    }

    /**
     * 模拟按下返回键
     *
     * @return true表示执行成功，false表示失败
     */
    public boolean pressBack() {
        return mInputController.pressBack();
    }

    /**
     * 模拟按下Home键，返回主屏幕
     *
     * @return true表示执行成功，false表示失败
     */
    public boolean pressHome() {
        return mInputController.pressHome();
    }

    /**
     * 根据包名启动应用
     *
     * @param packageName 要启动的应用包名
     * @return true表示执行成功，false表示失败
     */
    public boolean launchApp(String packageName) {
        return mInputController.launchApp(packageName);
    }

    /**
     * 系统设置观察者
     *
     * <p>监听AGENT_SERVICE_ENABLED和MINIMAX_API_KEY设置的变更，
     * 当设置变化时自动重新加载配置到服务中。
     *
     * @see ContentObserver
     */
    private class SettingsObserver extends ContentObserver {
        private final android.net.Uri mEnabledUri = Settings.Global.getUriFor(Settings.Global.AGENT_SERVICE_ENABLED);
        private final android.net.Uri mApiKeyUri = Settings.Global.getUriFor(Settings.Global.MINIMAX_API_KEY);
        private final android.net.Uri mVoiceWakeupEnabledUri = Settings.Global.getUriFor(Settings.Global.AGENT_VOICE_WAKEUP_ENABLED);
        private final android.net.Uri mHotwordUri = Settings.Global.getUriFor(Settings.Global.AGENT_HOTWORD);

        /**
         * 构造设置观察者
         *
         * @param handler 用于接收设置变更通知的Handler
         */
        SettingsObserver(Handler handler) {
            super(handler);
        }

        /**
         * 注册内容观察者
         *
         * <p>向ContentResolver注册监听以下设置的Uri：
         * <ul>
         *   <li>AGENT_SERVICE_ENABLED - Agent服务启用状态</li>
         *   <li>MINIMAX_API_KEY - API密钥</li>
         *   <li>AGENT_VOICE_WAKEUP_ENABLED - 语音唤醒启用状态</li>
         *   <li>AGENT_HOTWORD - 热词配置</li>
         * </ul>
         * 任何变更都会触发onChange回调。
         */
        void register() {
            ContentResolver resolver = mContext.getContentResolver();

            // 注册 Agent 服务相关设置观察
            resolver.registerContentObserver(mEnabledUri, false, this);
            resolver.registerContentObserver(mApiKeyUri, false, this);

            // 注册语音唤醒相关设置观察
            resolver.registerContentObserver(mVoiceWakeupEnabledUri, false, this);
            resolver.registerContentObserver(mHotwordUri, false, this);

            if (DEBUG) {
                Slog.d(TAG, "SettingsObserver registered - 监听 4 个设置项");
            }
        }

        @Override
        /**
         * 设置变更回调
         *
         * <p>当监听的设置发生变更时被调用。根据变更的设置类型：
         * <ul>
         *   <li>AGENT_SERVICE_ENABLED - 重新加载设置并更新语音唤醒状态</li>
         *   <li>MINIMAX_API_KEY - 重新加载API密钥</li>
         *   <li>AGENT_VOICE_WAKEUP_ENABLED - 更新语音唤醒启用状态</li>
         *   <li>AGENT_HOTWORD - 更新热词配置</li>
         * </ul>
         *
         * @param selfChange 是否是自发变更
         * @param uri 变更的Content Uri
         */
        public void onChange(boolean selfChange, android.net.Uri uri) {
            if (DEBUG) {
                Slog.i(TAG, "设置变更通知: " + uri);
            }

            if (mEnabledUri.equals(uri)) {
                // Agent 服务启用状态变更
                if (DEBUG) {
                    Slog.d(TAG, "Agent服务启用状态变更");
                }
                loadSettings();
                // 根据新的启用状态更新语音唤醒
                if (mEnabled) {
                    startVoiceWakeupIfEnabled();
                } else {
                    stopVoiceWakeup();
                }

            } else if (mApiKeyUri.equals(uri)) {
                // API 密钥变更
                if (DEBUG) {
                    Slog.d(TAG, "API密钥变更");
                }
                loadSettings();

            } else if (mVoiceWakeupEnabledUri.equals(uri)) {
                // 语音唤醒启用状态变更
                if (DEBUG) {
                    Slog.i(TAG, "语音唤醒启用状态变更");
                }
                // 重新加载语音唤醒设置
                if (mVoiceWakeup != null) {
                    mVoiceWakeup.reloadSettings();
                    // 根据新的启用状态更新语音唤醒
                    if (mVoiceWakeup.isEnabled() && mEnabled) {
                        startVoiceWakeupIfEnabled();
                    } else {
                        stopVoiceWakeup();
                    }
                }

            } else if (mHotwordUri.equals(uri)) {
                // 热词变更
                if (DEBUG) {
                    Slog.i(TAG, "热词配置变更");
                }
                if (mVoiceWakeup != null) {
                    mVoiceWakeup.reloadSettings();
                    Slog.i(TAG, "热词已更新为: " + mVoiceWakeup.getHotword());
                }

            } else {
                if (DEBUG) {
                    Slog.w(TAG, "未识别的设置变更: " + uri);
                }
            }
        }
    }
}
