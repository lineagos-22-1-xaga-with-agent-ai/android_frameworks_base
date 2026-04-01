package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.List;

public class AgentInputController {

    private static final String TAG = "AgentInputController";

    private static final int SWIPE_DURATION_MS = 300;
    private static final int TEXT_INPUT_DELAY_MS = 50;

    private final Context mContext;
    private final InputManager mInputManager;

    public AgentInputController(@NonNull Context context) {
        mContext = context;
        mInputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
    }

    public boolean click(int x, int y) {
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();

        MotionEvent downEvent = MotionEvent.obtain(downTime, eventTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent upEvent = MotionEvent.obtain(downTime, eventTime + 100,
                MotionEvent.ACTION_UP, x, y, 0);

        try {
            int result1 = mInputManager.injectInputEvent(downEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            int result2 = mInputManager.injectInputEvent(upEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            return result1 == 0 && result2 == 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject click event", e);
            return false;
        } finally {
            downEvent.recycle();
            upEvent.recycle();
        }
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        if (duration <= 0) {
            duration = SWIPE_DURATION_MS;
        }

        long downTime = SystemClock.uptimeMillis();
        long eventTime = downTime;

        int steps = 10;
        int delay = duration / steps;

        float dx = (float) (x2 - x1) / steps;
        float dy = (float) (y2 - y1) / steps;

        MotionEvent events[] = new MotionEvent[steps + 2];
        events[0] = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x1, y1, 0);

        for (int i = 1; i <= steps; i++) {
            eventTime += delay;
            float x = x1 + dx * i;
            float y = y1 + dy * i;
            events[i] = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0);
        }

        eventTime += delay;
        events[steps + 1] = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x2, y2, 0);

        try {
            for (MotionEvent event : events) {
                int result = mInputManager.injectInputEvent(event,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                if (result != 0) {
                    return false;
                }
                if (event.getAction() != MotionEvent.ACTION_MOVE) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject swipe event", e);
            return false;
        } finally {
            for (MotionEvent event : events) {
                event.recycle();
            }
        }
    }

    public boolean inputText(@NonNull String text) {
        if (text.isEmpty()) {
            return true;
        }

        try {
            for (char c : text.toCharArray()) {
                if (!inputCharacter(c)) {
                    return false;
                }
                try {
                    Thread.sleep(TEXT_INPUT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to input text", e);
            return false;
        }
    }

    private boolean inputCharacter(char character) {
        int code = Character.toUpperCase(character);

        KeyEvent downEvent = new KeyEvent(KeyEvent.ACTION_DOWN, code);
        KeyEvent upEvent = new KeyEvent(KeyEvent.ACTION_UP, code);

        try {
            int result1 = mInputManager.injectInputEvent(downEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            int result2 = mInputManager.injectInputEvent(upEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            return result1 == 0 && result2 == 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject character", e);
            return false;
        }
    }

    public boolean pressBack() {
        return injectKeyEvent(KeyEvent.KEYCODE_BACK);
    }

    public boolean pressHome() {
        return injectKeyEvent(KeyEvent.KEYCODE_HOME);
    }

    public boolean pressEnter() {
        return injectKeyEvent(KeyEvent.KEYCODE_ENTER);
    }

    public boolean pressDelete() {
        return injectKeyEvent(KeyEvent.KEYCODE_DEL);
    }

    private boolean injectKeyEvent(int keyCode) {
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();

        KeyEvent downEvent = new KeyEvent(downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent upEvent = new KeyEvent(downTime, eventTime + 50, KeyEvent.ACTION_UP, keyCode, 0);

        try {
            int result1 = mInputManager.injectInputEvent(downEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            int result2 = mInputManager.injectInputEvent(upEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            return result1 == 0 && result2 == 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject key event", e);
            return false;
        }
    }

    public boolean launchApp(@NonNull String packageName) {
        if (packageName.isEmpty()) {
            return false;
        }

        PackageManager pm = mContext.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(packageName);

        if (launchIntent == null) {
            List<ResolveInfo> apps = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    PackageManager.MATCH_DEFAULT_ONLY);

            for (ResolveInfo info : apps) {
                if (info.activityInfo.packageName.equalsIgnoreCase(packageName)) {
                    launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
                    break;
                }
            }
        }

        if (launchIntent == null) {
            Log.e(TAG, "No launch intent found for package: " + packageName);
            return false;
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            mContext.startActivity(launchIntent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch app: " + packageName, e);
            return false;
        }
    }
}
