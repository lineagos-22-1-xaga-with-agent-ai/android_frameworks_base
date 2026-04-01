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

    private final AtomicBoolean mProcessing = new AtomicBoolean(false);

    private SettingsObserver mSettingsObserver;

    public AgentService(Context context) {
        super(context);
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = mHandler::post;

        mScreenCapture = new AgentScreenCapture(context);
        mInputController = new AgentInputController(context);
        mMiniMaxClient = new MiniMaxClient();

        registerTools();
    }

    private void registerTools() {
        mMiniMaxClient.registerTool(new AgentTools.ScreenshotTool(mScreenCapture::takeScreenshot));
        mMiniMaxClient.registerTool(new AgentTools.ClickTool(mInputController));
        mMiniMaxClient.registerTool(new AgentTools.SwipeTool(mInputController));
        mMiniMaxClient.registerTool(new AgentTools.InputTextTool(mInputController));
        mMiniMaxClient.registerTool(new AgentTools.LaunchAppTool(mInputController));
        mMiniMaxClient.registerTool(new AgentTools.PressBackTool(mInputController));
        mMiniMaxClient.registerTool(new AgentTools.PressHomeTool(mInputController));
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.d(TAG, "Starting AgentService");
        }
        publishBinderService(SERVICE_NAME, mBinder);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mSettingsObserver = new SettingsObserver(mHandler);
            mSettingsObserver.register();
            loadSettings();
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
            Slog.d(TAG, "Agent enabled: " + mEnabled);
            Slog.d(TAG, "API key configured: " + (mApiKey != null && !mApiKey.isEmpty()));
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
            Slog.d(TAG, "sendTask: " + task);
        }

        boolean enabled;
        String apiKey;
        synchronized (mLock) {
            enabled = mEnabled;
            apiKey = mApiKey;
        }

        if (!enabled) {
            try {
                callback.onError("Agent service is not enabled");
            } catch (RemoteException e) {
            }
            return;
        }

        if (apiKey == null || apiKey.isEmpty()) {
            try {
                callback.onError("API key not configured");
            } catch (RemoteException e) {
            }
            return;
        }

        if (!mProcessing.compareAndSet(false, true)) {
            try {
                callback.onError("Agent is already processing a task");
            } catch (RemoteException e) {
            }
            return;
        }

        final IAgentServiceCallback finalCallback = callback;
        mExecutor.execute(() -> {
            try {
                byte[] screenshot = mScreenCapture.takeScreenshotAsPng();

                mMiniMaxClient.sendMessage(task, screenshot, new Callback() {
                    private void broadcast(Runnable r) {
                        mExecutor.execute(r);
                    }

                    @Override
                    public void onThinking(String thinking) {
                        broadcast(() -> {
                            try {
                                finalCallback.onThinking(thinking);
                            } catch (RemoteException e) {
                            }
                        });
                    }

                    @Override
                    public void onText(String text) {
                        broadcast(() -> {
                            try {
                                finalCallback.onText(text);
                            } catch (RemoteException e) {
                            }
                        });
                    }

                    @Override
                    public void onToolCall(String toolName, Bundle arguments) {
                        broadcast(() -> {
                            try {
                                AgentTool tool = findTool(toolName);
                                Bundle result;
                                if (tool != null) {
                                    result = tool.execute(arguments);
                                } else {
                                    result = new Bundle();
                                    result.putString("status", "error");
                                    result.putString("message", "Tool not found: " + toolName);
                                }
                                finalCallback.onToolResult(toolName, result);
                            } catch (RemoteException e) {
                            }
                        });
                    }

                    @Override
                    public void onComplete() {
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
                Slog.e(TAG, "Error processing task", e);
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
        }
    }

    public void setApiKey(String apiKey) {
        synchronized (mLock) {
            mApiKey = apiKey;
            mMiniMaxClient.setApiKey(apiKey);
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.MINIMAX_API_KEY, apiKey);
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
        }

        @Override
        public void onChange(boolean selfChange, android.net.Uri uri) {
            if (mEnabledUri.equals(uri) || mApiKeyUri.equals(uri)) {
                loadSettings();
            }
        }
    }
}
