package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.List;

/**
 * 输入控制器，负责模拟用户输入操作。
 *
 * <p>此类实现了AgentTools.InputController接口，提供了一系列模拟用户交互的方法，
 * 包括点击、滑动、文本输入、按键操作和应用启动等功能。主要用于AI代理服务中
 * 需要自动化执行用户操作的场景。</p>
 *
 * @author AI Agent System
 */
public class AgentInputController implements AgentTools.InputController {

    private static final String TAG = "AgentInputController";

    /** 默认滑动持续时间（毫秒） */
    private static final int SWIPE_DURATION_MS = 300;
    /** 文本输入字符间隔（毫秒） */
    private static final int TEXT_INPUT_DELAY_MS = 50;

    private final Context mContext;
    private final InputManager mInputManager;

    /**
     * 构造方法，初始化输入控制器。
     *
     * <p>获取InputManager服务用于后续的输入事件注入操作。</p>
     *
     * @param context 应用上下文，用于获取系统服务
     */
    public AgentInputController(@NonNull Context context) {
        mContext = context;
        mInputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        Slog.i(TAG, "AgentInputController 初始化完成");
    }

    /**
     * 在指定坐标位置执行点击操作。
     *
     * <p>模拟一次完整的触摸事件序列：ACTION_DOWN后跟ACTION_UP，
     * 两次事件间隔100毫秒。</p>
     *
     * @param x 点击的X坐标（像素）
     * @param y 点击的Y坐标（像素）
     * @return true表示点击事件注入成功，false表示失败
     */
    public boolean click(int x, int y) {
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();

        Slog.d(TAG, "执行点击操作: (" + x + ", " + y + ")");

        MotionEvent downEvent = MotionEvent.obtain(downTime, eventTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent upEvent = MotionEvent.obtain(downTime, eventTime + 100,
                MotionEvent.ACTION_UP, x, y, 0);

        try {
            boolean result1 = mInputManager.injectInputEvent(downEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            boolean result2 = mInputManager.injectInputEvent(upEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);

            if (result1 && result2) {
                Slog.i(TAG, "点击操作成功: (" + x + ", " + y + ")");
            } else {
                Slog.w(TAG, "点击操作部分成功但结果异常");
            }
            return result1 && result2;
        } catch (Exception e) {
            Slog.e(TAG, "点击事件注入失败: (" + x + ", " + y + ")", e);
            return false;
        } finally {
            downEvent.recycle();
            upEvent.recycle();
        }
    }

    /**
     * 执行滑动操作。
     *
     * <p>从起点(x1, y1)滑动到终点(x2, y2)，整个滑动过程分为10个步骤，
     * 每步之间有适当的延迟以确保平滑的滑动效果。</p>
     *
     * @param x1 起始点X坐标
     * @param y1 起始点Y坐标
     * @param x2 终点X坐标
     * @param y2 终点Y坐标
     * @param duration 滑动持续时间（毫秒），小于等于0时使用默认值300ms
     * @return true表示滑动操作执行成功，false表示失败
     */
    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        if (duration <= 0) {
            duration = SWIPE_DURATION_MS;
            Slog.d(TAG, "使用默认滑动持续时间: " + duration + "ms");
        }

        Slog.d(TAG, "执行滑动操作: (" + x1 + ", " + y1 + ") -> (" + x2 + ", " + y2 + "), 持续时间: " + duration + "ms");

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
                boolean result = mInputManager.injectInputEvent(event,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                if (!result) {
                    Slog.w(TAG, "滑动事件注入返回false，终止滑动");
                    return false;
                }
                if (event.getAction() != MotionEvent.ACTION_MOVE) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Slog.w(TAG, "滑动过程中线程被中断");
                        return false;
                    }
                }
            }
            Slog.i(TAG, "滑动操作执行成功");
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "滑动事件注入过程发生异常", e);
            return false;
        } finally {
            for (MotionEvent event : events) {
                event.recycle();
            }
        }
    }

    /**
     * 输入文本内容。
     *
     * <p>将字符串逐字符转换为按键事件并注入系统，每个字符之间有50ms的延迟，
     * 以模拟真实的打字速度。对于非ASCII字符可能会有限制。</p>
     *
     * @param text 要输入的文本内容
     * @return true表示文本输入成功，false表示失败或被中断
     */
    public boolean inputText(@NonNull String text) {
        if (text.isEmpty()) {
            Slog.d(TAG, "输入文本为空，直接返回成功");
            return true;
        }

        Slog.d(TAG, "开始输入文本，长度: " + text.length() + " 字符");

        try {
            for (char c : text.toCharArray()) {
                if (!inputCharacter(c)) {
                    Slog.w(TAG, "输入字符 '" + c + "' 失败");
                    return false;
                }
                try {
                    Thread.sleep(TEXT_INPUT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Slog.w(TAG, "文本输入过程中线程被中断");
                    return false;
                }
            }
            Slog.i(TAG, "文本输入成功: \"" + text + "\"");
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "文本输入过程发生异常", e);
            return false;
        }
    }

    /**
     * 输入单个字符。
     *
     * <p>将字符转换为对应的KeyEvent并注入系统，字符会被转换为大写。
     * 此方法主要用于inputText的内部实现。</p>
     *
     * @param character 要输入的字符
     * @return true表示字符输入成功，false表示失败
     */
    private boolean inputCharacter(char character) {
        int code = Character.toUpperCase(character);

        KeyEvent downEvent = new KeyEvent(KeyEvent.ACTION_DOWN, code);
        KeyEvent upEvent = new KeyEvent(KeyEvent.ACTION_UP, code);

        try {
            boolean result1 = mInputManager.injectInputEvent(downEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            boolean result2 = mInputManager.injectInputEvent(upEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            return result1 && result2;
        } catch (Exception e) {
            Slog.e(TAG, "注入字符 '" + character + "' (code=" + code + ") 失败", e);
            return false;
        }
    }

    /**
     * 按下返回键。
     *
     * @return true表示按键注入成功，false表示失败
     */
    public boolean pressBack() {
        Slog.d(TAG, "按下返回键");
        return injectKeyEvent(KeyEvent.KEYCODE_BACK);
    }

    /**
     * 按下主页键。
     *
     * @return true表示按键注入成功，false表示失败
     */
    public boolean pressHome() {
        Slog.d(TAG, "按下主页键");
        return injectKeyEvent(KeyEvent.KEYCODE_HOME);
    }

    /**
     * 按下回车键。
     *
     * @return true表示按键注入成功，false表示失败
     */
    public boolean pressEnter() {
        Slog.d(TAG, "按下回车键");
        return injectKeyEvent(KeyEvent.KEYCODE_ENTER);
    }

    /**
     * 按下删除键。
     *
     * @return true表示按键注入成功，false表示失败
     */
    public boolean pressDelete() {
        Slog.d(TAG, "按下删除键");
        return injectKeyEvent(KeyEvent.KEYCODE_DEL);
    }

    /**
     * 注入指定的按键事件。
     *
     * <p>内部辅助方法，用于创建和注入按键的DOWN和UP事件序列，
     * 两次事件间隔50毫秒。</p>
     *
     * @param keyCode 要注入的按键码（如KeyEvent.KEYCODE_*）
     * @return true表示按键注入成功，false表示失败
     */
    private boolean injectKeyEvent(int keyCode) {
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();

        KeyEvent downEvent = new KeyEvent(downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent upEvent = new KeyEvent(downTime, eventTime + 50, KeyEvent.ACTION_UP, keyCode, 0);

        try {
            boolean result1 = mInputManager.injectInputEvent(downEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            boolean result2 = mInputManager.injectInputEvent(upEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);

            if (result1 && result2) {
                Slog.d(TAG, "按键事件注入成功: keyCode=" + keyCode);
            }
            return result1 && result2;
        } catch (Exception e) {
            Slog.e(TAG, "按键事件注入失败: keyCode=" + keyCode, e);
            return false;
        }
    }

    /**
     * 启动指定包名的应用。
     *
     * <p>首先尝试通过PackageManager获取应用的启动Intent，如果获取不到，
     * 则查询所有LAUNCHER类别的Activity来匹配包名。找到启动Intent后，
     * 以FLAG_ACTIVITY_NEW_TASK和FLAG_ACTIVITY_CLEAR_TOP标志启动应用。</p>
     *
     * @param packageName 要启动的应用包名
     * @return true表示应用启动成功，false表示失败
     */
    public boolean launchApp(@NonNull String packageName) {
        if (packageName.isEmpty()) {
            Slog.w(TAG, "包名为空，无法启动应用");
            return false;
        }

        Slog.d(TAG, "正在启动应用: " + packageName);

        PackageManager pm = mContext.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(packageName);

        if (launchIntent == null) {
            Slog.d(TAG, "直接获取LaunchIntent失败，尝试通过查询Launcher Activity匹配包名");

            List<ResolveInfo> apps = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    PackageManager.MATCH_DEFAULT_ONLY);

            for (ResolveInfo info : apps) {
                if (info.activityInfo.packageName.equalsIgnoreCase(packageName)) {
                    launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
                    Slog.d(TAG, "通过Launcher Activity匹配到包名: " + info.activityInfo.packageName);
                    break;
                }
            }
        }

        if (launchIntent == null) {
            Slog.e(TAG, "无法找到包名对应的启动Intent: " + packageName);
            return false;
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            mContext.startActivity(launchIntent);
            Slog.i(TAG, "应用启动成功: " + packageName);
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "启动应用时发生异常: " + packageName, e);
            return false;
        }
    }
}
