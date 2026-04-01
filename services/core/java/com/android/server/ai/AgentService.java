package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
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

public class AgentService extends SystemService {
    private static final String TAG = "AgentService";
    private static final boolean DEBUG = true;

    public static final String SERVICE_NAME = "agent";

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

    private final AtomicBoolean mProcessing = new AtomicBoolean(false);

    private SettingsObserver mSettingsObserver;

    public AgentService(Context context) {
        super(context);
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = mHandler::post;

        if (DEBUG) {
            Log.d(TAG, "AgentService constructor starting...");
        }

        mScreenCapture = new AgentScreenCapture(context);
        if (DEBUG) {
            Log.d(TAG, "AgentScreenCapture initialized");
        }

        mInputController = new AgentInputController(context);
        if (DEBUG) {
            Log.d(TAG, "AgentInputController initialized");
        }

        mMiniMaxClient = new MiniMaxClient();
        if (DEBUG) {
            Log.d(TAG, "MiniMaxClient initialized");
        }

        mUserMemory = new UserMemoryManager(context);
        if (DEBUG) {
            Log.d(TAG, "UserMemoryManager initialized");
        }

        mTaskScheduler = new TaskScheduler(context);
        if (DEBUG) {
            Log.d(TAG, "TaskScheduler initialized");
        }

        registerTools();
        
        if (DEBUG) {
            Log.d(TAG, "AgentService constructor completed");
        }
    }

    private void registerTools() {
        if (DEBUG) {
            Log.d(TAG, "Registering tools...");
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
            Log.d(TAG, "Registered: screenshot tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.ClickTool(mInputController, memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: click tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.SwipeTool(mInputController, memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: swipe tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.InputTextTool(mInputController, memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: input_text tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.LaunchAppTool(mInputController, memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: launch_app tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.PressBackTool(mInputController, memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: press_back tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.PressHomeTool(mInputController, memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: press_home tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.RememberTool(memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: remember tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.RecallTool(memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: recall tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.SetPreferenceTool(memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: set_preference tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.GetPreferenceTool(memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: get_preference tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.ScheduleTaskTool(schedulerInterface));
        if (DEBUG) {
            Log.d(TAG, "Registered: schedule_task tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.ScheduleRecurringTool(schedulerInterface));
        if (DEBUG) {
            Log.d(TAG, "Registered: schedule_recurring tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.CancelTaskTool(schedulerInterface));
        if (DEBUG) {
            Log.d(TAG, "Registered: cancel_task tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.GetPendingTasksTool(schedulerInterface));
        if (DEBUG) {
            Log.d(TAG, "Registered: get_pending_tasks tool");
        }

        mMiniMaxClient.registerTool(new AgentTools.GetContextTool(memoryManager));
        if (DEBUG) {
            Log.d(TAG, "Registered: get_context tool");
        }

        mTaskScheduler.setTaskExecutor((taskId, taskDescription, callback) -> {
            if (DEBUG) {
                Log.d(TAG, "Executing scheduled task: " + taskId + ", desc=" + taskDescription);
            }
            executeScheduledTask(taskId, taskDescription, callback);
        });

        mTaskScheduler.setTaskChangeListener(new TaskScheduler.TaskChangeListener() {
            @Override
            public void onTaskScheduled(TaskScheduler.ScheduledTask task) {
                if (DEBUG) {
                    Log.d(TAG, "Task scheduled: " + task.taskId);
                }
            }

            @Override
            public void onTaskStarted(TaskScheduler.ScheduledTask task) {
                if (DEBUG) {
                    Log.d(TAG, "Task started: " + task.taskId);
                }
            }

            @Override
            public void onTaskCompleted(TaskScheduler.ScheduledTask task, String result) {
                if (DEBUG) {
                    Log.d(TAG, "Task completed: " + task.taskId + ", result=" + result);
                }
            }

            @Override
            public void onTaskFailed(TaskScheduler.ScheduledTask task, String error) {
                Log.e(TAG, "Task failed: " + task.taskId + ", error=" + error);
            }

            @Override
            public void onTaskCancelled(TaskScheduler.ScheduledTask task) {
                if (DEBUG) {
                    Log.d(TAG, "Task cancelled: " + task.taskId);
                }
            }
        });

        if (DEBUG) {
            Log.d(TAG, "All tools registered successfully");
        }
    }

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
                            Log.d(TAG, "Task thinking: " + thinking);
                        }
                    }

                    @Override
                    public void onText(String text) {
                        if (DEBUG) {
                            Log.d(TAG, "Task text: " + text);
                        }
                    }

                    @Override
                    public void onToolCall(String toolName, Bundle arguments) {
                        if (DEBUG) {
                            Log.d(TAG, "Task tool call: " + toolName);
                        }
                        AgentTool tool = findTool(toolName);
                        if (tool != null) {
                            try {
                                Bundle result = tool.execute(arguments);
                                if (DEBUG) {
                                    Log.d(TAG, "Tool result: " + toolName + " -> " + result.getString("status"));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Tool execution failed: " + toolName, e);
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
                Log.e(TAG, "Failed to execute scheduled task: " + taskId, e);
                callback.onFailure(e.getMessage());
            }
        });
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Log.d(TAG, "onStart called");
        }
        
        publishBinderService(SERVICE_NAME, mBinder);
        
        if (DEBUG) {
            Log.d(TAG, "AgentService published with name: " + SERVICE_NAME);
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (DEBUG) {
            Log.d(TAG, "onBootPhase: " + phase);
        }
        
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            if (DEBUG) {
                Log.d(TAG, "PHASE_SYSTEM_SERVICES_READY - initializing settings observer");
            }
            
            mSettingsObserver = new SettingsObserver(mHandler);
            mSettingsObserver.register();
            loadSettings();
            
            if (DEBUG) {
                Log.d(TAG, "Settings loaded, AgentService ready");
            }
        }
    }

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
            Log.d(TAG, "loadSettings - enabled: " + mEnabled + ", apiKey configured: " + (mApiKey != null && !mApiKey.isEmpty()));
        }
    }

    private final IBinder mBinder = new AgentServiceBinder();

    private class AgentServiceBinder extends Binder {
        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply,
                int flags) throws RemoteException {
            switch (code) {
                case TRANSACT_SEND_TASK: {
                    String task = data.readString();
                    IAgentServiceCallback callback = IAgentServiceCallback.Stub.asInterface(
                            data.readStrongBinder());
                    sendTask(task, callback);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACT_IS_ENABLED: {
                    reply.writeBoolean(isEnabled());
                    return true;
                }
                case TRANSACT_SET_ENABLED: {
                    boolean enabled = data.readInt() == 1;
                    setEnabled(enabled);
                    return true;
                }
                case TRANSACT_SET_API_KEY: {
                    String apiKey = data.readString();
                    setApiKey(apiKey);
                    return true;
                }
                case TRANSACT_GET_API_KEY: {
                    reply.writeString(getApiKey());
                    return true;
                }
                case TRANSACT_TAKE_SCREENSHOT: {
                    Bitmap bitmap = takeScreenshot();
                    if (bitmap != null) {
                        bitmap.writeToParcel(reply, 0);
                    }
                    return true;
                }
                case TRANSACT_CLICK: {
                    int x = data.readInt();
                    int y = data.readInt();
                    reply.writeInt(click(x, y) ? 1 : 0);
                    return true;
                }
                case TRANSACT_SWIPE: {
                    int x1 = data.readInt();
                    int y1 = data.readInt();
                    int x2 = data.readInt();
                    int y2 = data.readInt();
                    int duration = data.readInt();
                    reply.writeInt(swipe(x1, y1, x2, y2, duration) ? 1 : 0);
                    return true;
                }
                case TRANSACT_INPUT_TEXT: {
                    String text = data.readString();
                    reply.writeInt(inputText(text) ? 1 : 0);
                    return true;
                }
                case TRANSACT_PRESS_BACK: {
                    reply.writeInt(pressBack() ? 1 : 0);
                    return true;
                }
                case TRANSACT_PRESS_HOME: {
                    reply.writeInt(pressHome() ? 1 : 0);
                    return true;
                }
                case TRANSACT_LAUNCH_APP: {
                    String packageName = data.readString();
                    reply.writeInt(launchApp(packageName) ? 1 : 0);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    private static final int TRANSACT_SEND_TASK = 1;
    private static final int TRANSACT_IS_ENABLED = 2;
    private static final int TRANSACT_SET_ENABLED = 3;
    private static final int TRANSACT_SET_API_KEY = 4;
    private static final int TRANSACT_GET_API_KEY = 5;
    private static final int TRANSACT_TAKE_SCREENSHOT = 6;
    private static final int TRANSACT_CLICK = 7;
    private static final int TRANSACT_SWIPE = 8;
    private static final int TRANSACT_INPUT_TEXT = 9;
    private static final int TRANSACT_PRESS_BACK = 10;
    private static final int TRANSACT_PRESS_HOME = 11;
    private static final int TRANSACT_LAUNCH_APP = 12;

    public interface IAgentServiceCallback {
        void onThinking(String thinking);
        void onText(String text);
        void onToolResult(String toolName, Bundle result);
        void onComplete();
        void onError(String error);
    }

    private void sendTask(String task, IAgentServiceCallback callback) {
        if (DEBUG) {
            Log.d(TAG, "sendTask received: " + task);
        }

        boolean enabled;
        String apiKey;
        synchronized (mLock) {
            enabled = mEnabled;
            apiKey = mApiKey;
        }

        if (!enabled) {
            if (DEBUG) {
                Log.w(TAG, "Agent service is not enabled");
            }
            try {
                callback.onError("Agent service is not enabled");
            } catch (RemoteException e) {
            }
            return;
        }

        if (apiKey == null || apiKey.isEmpty()) {
            if (DEBUG) {
                Log.w(TAG, "API key not configured");
            }
            try {
                callback.onError("API key not configured");
            } catch (RemoteException e) {
            }
            return;
        }

        if (!mProcessing.compareAndSet(false, true)) {
            if (DEBUG) {
                Log.w(TAG, "Agent is already processing a task");
            }
            try {
                callback.onError("Agent is already processing a task");
            } catch (RemoteException e) {
            }
            return;
        }

        mUserMemory.addConversationTurn("user", task);

        final IAgentServiceCallback finalCallback = callback;
        mExecutor.execute(() -> {
            if (DEBUG) {
                Log.d(TAG, "Processing task...");
            }
            
            try {
                byte[] screenshot = mScreenCapture.takeScreenshotAsPng();

                if (DEBUG) {
                    Log.d(TAG, "Screenshot taken, sending to MiniMax...");
                }

                mMiniMaxClient.sendMessage(task, screenshot, new Callback() {
                    private void broadcast(Runnable r) {
                        mExecutor.execute(r);
                    }

                    @Override
                    public void onThinking(String thinking) {
                        if (DEBUG) {
                            Log.d(TAG, "Thinking: " + thinking);
                        }
                        broadcast(() -> {
                            try {
                                finalCallback.onThinking(thinking);
                            } catch (RemoteException e) {
                            }
                        });
                    }

                    @Override
                    public void onText(String text) {
                        if (DEBUG) {
                            Log.d(TAG, "Text: " + text);
                        }
                        mUserMemory.addConversationTurn("assistant", text);
                        broadcast(() -> {
                            try {
                                finalCallback.onText(text);
                            } catch (RemoteException e) {
                            }
                        });
                    }

                    @Override
                    public void onToolCall(String toolName, Bundle arguments) {
                        if (DEBUG) {
                            Log.d(TAG, "Tool call: " + toolName + " with args: " + arguments);
                        }
                        broadcast(() -> {
                            try {
                                AgentTool tool = findTool(toolName);
                                Bundle result;
                                if (tool != null) {
                                    if (DEBUG) {
                                        Log.d(TAG, "Executing tool: " + toolName);
                                    }
                                    result = tool.execute(arguments);
                                    if (DEBUG) {
                                        Log.d(TAG, "Tool result: " + toolName + " -> " + result.getString("status"));
                                    }
                                } else {
                                    result = new Bundle();
                                    result.putString("status", "error");
                                    result.putString("message", "Tool not found: " + toolName);
                                    Log.e(TAG, "Tool not found: " + toolName);
                                }
                                finalCallback.onToolResult(toolName, result);
                            } catch (Exception e) {
                                Log.e(TAG, "Tool execution failed: " + toolName, e);
                                try {
                                    Bundle errorResult = new Bundle();
                                    errorResult.putString("status", "error");
                                    errorResult.putString("message", e.getMessage());
                                    finalCallback.onToolResult(toolName, errorResult);
                                } catch (RemoteException ex) {
                                }
                            }
                        });
                    }

                    @Override
                    public void onComplete() {
                        if (DEBUG) {
                            Log.d(TAG, "Task complete");
                        }
                        broadcast(() -> {
                            try {
                                finalCallback.onComplete();
                            } catch (RemoteException e) {
                            }
                            mProcessing.set(false);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Task error: " + error);
                        broadcast(() -> {
                            try {
                                finalCallback.onError(error);
                            } catch (RemoteException e) {
                            }
                            mProcessing.set(false);
                        });
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing task", e);
                try {
                    finalCallback.onError(e.getMessage());
                } catch (RemoteException ex) {
                }
                mProcessing.set(false);
            }
        });
    }

    @Nullable
    private AgentTool findTool(String name) {
        try {
            java.lang.reflect.Field field = MiniMaxClient.class.getDeclaredField("mTools");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, AgentTool> tools = (java.util.Map<String, AgentTool>) field.get(mMiniMaxClient);
            return tools.get(name);
        } catch (Exception e) {
            Log.w(TAG, "Failed to find tool: " + name, e);
            return null;
        }
    }

    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    public void setEnabled(boolean enabled) {
        synchronized (mLock) {
            mEnabled = enabled;
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AGENT_SERVICE_ENABLED, enabled ? 1 : 0);
            if (DEBUG) {
                Log.d(TAG, "setEnabled: " + enabled);
            }
        }
    }

    public void setApiKey(String apiKey) {
        synchronized (mLock) {
            mApiKey = apiKey;
            mMiniMaxClient.setApiKey(apiKey);
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.MINIMAX_API_KEY, apiKey);
            if (DEBUG) {
                Log.d(TAG, "setApiKey: " + (apiKey != null ? "configured" : "null"));
            }
        }
    }

    public String getApiKey() {
        synchronized (mLock) {
            return mApiKey;
        }
    }

    public Bitmap takeScreenshot() {
        return mScreenCapture.takeScreenshot();
    }

    public boolean click(int x, int y) {
        return mInputController.click(x, y);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        return mInputController.swipe(x1, y1, x2, y2, duration);
    }

    public boolean inputText(String text) {
        return mInputController.inputText(text);
    }

    public boolean pressBack() {
        return mInputController.pressBack();
    }

    public boolean pressHome() {
        return mInputController.pressHome();
    }

    public boolean launchApp(String packageName) {
        return mInputController.launchApp(packageName);
    }

    private class SettingsObserver extends ContentObserver {
        private final android.net.Uri mEnabledUri = Settings.Global.getUriFor(Settings.Global.AGENT_SERVICE_ENABLED);
        private final android.net.Uri mApiKeyUri = Settings.Global.getUriFor(Settings.Global.MINIMAX_API_KEY);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void register() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(mEnabledUri, false, this);
            resolver.registerContentObserver(mApiKeyUri, false, this);
            if (DEBUG) {
                Log.d(TAG, "SettingsObserver registered");
            }
        }

        @Override
        public void onChange(boolean selfChange, android.net.Uri uri) {
            if (DEBUG) {
                Log.d(TAG, "Settings changed: " + uri);
            }
            if (mEnabledUri.equals(uri) || mApiKeyUri.equals(uri)) {
                loadSettings();
            }
        }
    }
}
