package com.android.server.ai;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.voiceinteraction.VoiceInteractionManagerService;

import java.util.List;
import java.util.concurrent.Executor;

public class VoiceWakeupManager {
    private static final String TAG = "VoiceWakeup";
    private static final boolean DEBUG = true;

    private static final String ACTION_VOICE_WAKEUP = "android.intent.action.VOICE_WAKEUP";
    private static final String VOICE_INTERACTION_SERVICE = "voiceinteraction";

    private final Context mContext;
    private final Executor mExecutor;
    private final Handler mHandler;

    private VoiceInteractionManagerService mVoiceService;
    boolean mVoiceServiceBound = false;

    private VoiceWakeupListener mListener;
    private String mHotword;
    private boolean mEnabled = false;

    private final Object mLock = new Object();

    public interface VoiceWakeupListener {
        void onWakewordDetected(String hotword);
        void onVoiceCommand(String command);
        void onError(String error);
    }

    public VoiceWakeupManager(Context context) {
        mContext = context;
        mExecutor = UiThread.getExecutor();
        mHandler = new Handler(Looper.getMainLooper());

        if (DEBUG) {
            Log.d(TAG, "VoiceWakeupManager initializing...");
        }

        bindToVoiceService();
    }

    private void bindToVoiceService() {
        if (DEBUG) {
            Log.d(TAG, "Binding to VoiceInteractionService...");
        }

        Intent intent = new Intent();
        intent.setClassName("com.android.systemui",
                "com.android.systemui.voicinput.VoiceInteractionServiceImpl");

        try {
            mContext.bindServiceAsUser(intent, mVoiceServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY,
                    UserHandle.CURRENT);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to bind to voice service", e);
            tryFallbackToDefaultVoiceService();
        }
    }

    private void tryFallbackToDefaultVoiceService() {
        if (DEBUG) {
            Log.d(TAG, "Trying fallback to default voice service...");
        }

        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(android.service.voice.VoiceInteractionService.SERVICE_INTERFACE),
                PackageManager.MATCH_DEFAULT_ONLY);

        if (services != null && !services.isEmpty()) {
            ServiceInfo serviceInfo = services.get(0).serviceInfo;
            Intent intent = new Intent();
            intent.setClassName(serviceInfo.packageName, serviceInfo.name);

            try {
                mContext.bindServiceAsUser(intent, mVoiceServiceConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY,
                        UserHandle.CURRENT);
            } catch (SecurityException e) {
                Log.e(TAG, "Failed to bind to fallback voice service", e);
            }
        } else {
            if (DEBUG) {
                Log.w(TAG, "No voice interaction service found");
            }
        }
    }

    private final ServiceConnection mVoiceServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) {
                Log.d(TAG, "Voice service connected: " + name);
            }
            mVoiceServiceBound = true;
            try {
                android.os.ServiceManager.getService(VOICE_INTERACTION_SERVICE);
                if (DEBUG) {
                    Log.d(TAG, "VoiceInteractionService retrieved");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get VoiceInteractionService", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) {
                Log.d(TAG, "Voice service disconnected: " + name);
            }
            mVoiceServiceBound = false;
        }
    };

    public void setListener(VoiceWakeupListener listener) {
        mListener = listener;
    }

    public void setEnabled(boolean enabled) {
        synchronized (mLock) {
            mEnabled = enabled;
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.AGENT_VOICE_WAKEUP_ENABLED, enabled ? 1 : 0);

            if (DEBUG) {
                Log.d(TAG, "Voice wakeup enabled: " + enabled);
            }
        }
    }

    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    public void setHotword(String hotword) {
        synchronized (mLock) {
            mHotword = hotword;
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.AGENT_HOTWORD, hotword);

            if (DEBUG) {
                Log.d(TAG, "Hotword set: " + hotword);
            }
        }
    }

    public String getHotword() {
        synchronized (mLock) {
            return mHotword;
        }
    }

    public void startListening() {
        if (DEBUG) {
            Log.d(TAG, "Starting voice listening...");
        }

        if (!mEnabled) {
            if (DEBUG) {
                Log.w(TAG, "Voice wakeup not enabled");
            }
            return;
        }

        try {
            Intent intent = new Intent(ACTION_VOICE_WAKEUP);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);

            if (DEBUG) {
                Log.d(TAG, "Voice wakeup intent sent");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start voice listening", e);
            if (mListener != null) {
                mListener.onError("Failed to start voice listening: " + e.getMessage());
            }
        }
    }

    public void stopListening() {
        if (DEBUG) {
            Log.d(TAG, "Stopping voice listening...");
        }
    }

    public void processVoiceInput(String transcription) {
        if (DEBUG) {
            Log.d(TAG, "Processing voice input: " + transcription);
        }

        if (mListener != null) {
            mListener.onVoiceCommand(transcription);
        }
    }

    public void onWakewordDetected() {
        if (DEBUG) {
            Log.d(TAG, "Wakeword detected");
        }

        if (mListener != null) {
            mHandler.post(() -> {
                if (mListener != null) {
                    mListener.onWakewordDetected(mHotword != null ? mHotword : "hey assistant");
                }
            });
        }
    }

    public void unbind() {
        if (mVoiceServiceBound) {
            try {
                mContext.unbindService(mVoiceServiceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unbind voice service", e);
            }
            mVoiceServiceBound = false;
        }
    }

    public void loadSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mEnabled = Settings.Global.getInt(resolver,
                Settings.Global.AGENT_VOICE_WAKEUP_ENABLED, 0) == 1;
        mHotword = Settings.Global.getString(resolver,
                Settings.Global.AGENT_HOTWORD);

        if (mHotword == null || mHotword.isEmpty()) {
            mHotword = "hey assistant";
        }

        if (DEBUG) {
            Log.d(TAG, "Loaded settings - enabled: " + mEnabled + ", hotword: " + mHotword);
        }
    }
}
