package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AgentTools - AI 代理工具集
 *
 * <p>该类提供了一系列供 AI 代理使用的工具，包括：
 * <ul>
 *   <li>屏幕操作工具：截图、点击、滑动、输入文本、按返回键、按Home键、启动应用</li>
 *   <li>记忆管理工具：存储/检索记忆、设置/获取偏好设置</li>
 *   <li>任务调度工具：定时任务、周期性任务、取消任务、查询待执行任务</li>
 *   <li>上下文获取工具：获取屏幕历史、动作历史和对话历史</li>
 * </ul>
 *
 * <p>每个工具都实现了 {@link MiniMaxClient.AgentTool} 接口，
 * 可以注册到 {@link MiniMaxClient} 中供 AI 模型调用。
 *
 * @see MiniMaxClient.AgentTool
 */
public class AgentTools {

    private static final String TAG = "AgentTools";

    /**
     * ScreenshotProvider - 屏幕截图提供者接口
     *
     * <p>实现此接口来提供屏幕截图功能。
     */
    public interface ScreenshotProvider {
        /** 执行屏幕截图并返回 Bitmap 对象 */
        Bitmap takeScreenshot();
    }

    /**
     * InputController - 输入控制器接口
     *
     * <p>提供各种用户输入模拟功能，用于 AI 代理与设备交互。
     */
    public interface InputController {
        /** 在指定坐标点击 */
        boolean click(int x, int y);
        /** 从起点滑动到终点 */
        boolean swipe(int x1, int y1, int x2, int y2, int duration);
        /** 在当前焦点文本框输入文本 */
        boolean inputText(String text);
        /** 按返回键 */
        boolean pressBack();
        /** 按Home键 */
        boolean pressHome();
        /** 根据包名启动应用 */
        boolean launchApp(String packageName);
    }

    /**
     * MemoryManager - 记忆管理器接口
     *
     * <p>提供记忆存储和检索功能，包括：
     * <ul>
     *   <li>通用记忆存储和检索</li>
     *   <li>偏好设置管理</li>
     *   <li>屏幕/动作/对话历史记录</li>
     * </ul>
     */
    public interface MemoryManager {
        /** 存储记忆 */
        void remember(String key, String value, String category);
        /** 检索记忆（异步回调） */
        void recall(String key, String category, UserMemoryManager.RecallCallback callback);
        /** 设置偏好 */
        void setPreference(String category, String key, String value);
        /** 获取偏好（异步回调） */
        void getPreference(String category, String key, UserMemoryManager.RecallCallback callback);
        /** 获取屏幕历史（异步回调） */
        void getScreenHistory(UserMemoryManager.ScreenHistoryCallback callback);
        /** 获取动作历史（异步回调） */
        void getActionHistory(UserMemoryManager.ActionHistoryCallback callback);
        /** 获取对话历史（异步回调） */
        void getConversationHistory(UserMemoryManager.ConversationHistoryCallback callback);
        /** 添加屏幕历史记录 */
        void addScreenHistory(String description, byte[] screenshotHash);
        /** 添加动作历史记录 */
        void addActionHistory(String action, String target, String result);
        /** 添加对话记录 */
        void addConversationTurn(String role, String content);
    }

    /**
     * TaskSchedulerInterface - 任务调度器接口
     *
     * <p>提供定时任务调度功能，支持一次性任务和周期性任务。
     */
    public interface TaskSchedulerInterface {
        /** 调度一次性任务 */
        String scheduleTask(String description, long triggerAtMillis);
        /** 调度周期性任务 */
        String scheduleRecurringTask(String description, long intervalMillis);
        /** 取消任务 */
        boolean cancelTask(String taskId);
        /** 获取待执行任务数量 */
        int getPendingTaskCount();
    }

    /**
     * ScreenshotTool - 屏幕截图工具
     *
     * <p>用于捕获当前屏幕并保存到历史记录中。
     * AI 代理可以通过此工具获取屏幕状态以便做出决策。
     */
    public static class ScreenshotTool implements MiniMaxClient.AgentTool {
        private final ScreenshotProvider mProvider;
        private final MemoryManager mMemory;

        public ScreenshotTool(@NonNull ScreenshotProvider provider, @NonNull MemoryManager memory) {
            mProvider = provider;
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "screenshot";
        }

        @Override
        public String getDescription() {
            return "Take a screenshot of the current screen. Returns the screenshot as a bitmap. " +
                    "This also stores the screenshot in history for context.";
        }

        @Override
        public JSONObject getInputSchema() {
            return new JSONObject();
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            try {
                Bitmap bitmap = mProvider.takeScreenshot();
                if (bitmap != null) {
                    String desc = "Screenshot taken at " + System.currentTimeMillis() + 
                            ", size: " + bitmap.getWidth() + "x" + bitmap.getHeight();
                    
                    mMemory.addScreenHistory(desc, null);
                    
                    result.putString("status", "success");
                    result.putInt("width", bitmap.getWidth());
                    result.putInt("height", bitmap.getHeight());
                    result.putString("description", desc);
                } else {
                    result.putString("status", "error");
                    result.putString("message", "Failed to take screenshot");
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error taking screenshot", e);
                result.putString("status", "error");
                result.putString("message", e.getMessage());
            }
            return result;
        }
    }

    /**
     * ClickTool - 点击工具
     *
     * <p>在屏幕指定坐标位置执行点击操作。
     */
    public static class ClickTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param controller 输入控制器
         * @param memory 记忆管理器
         */
        public ClickTool(@NonNull InputController controller, @NonNull MemoryManager memory) {
            mController = controller;
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "click";
        }

        @Override
        public String getDescription() {
            return "Click at the specified coordinates on the screen.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("x", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "The x coordinate"))
                                .put("y", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "The y coordinate")))
                        .put("required", new JSONArray().put("x").put("y"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for click", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            int x = arguments.getInt("x", -1);
            int y = arguments.getInt("y", -1);

            if (x < 0 || y < 0) {
                Slog.w(TAG, "Invalid click coordinates: x=" + x + ", y=" + y);
                result.putString("status", "error");
                result.putString("message", "Invalid coordinates: x=" + x + ", y=" + y);
                return result;
            }

            Slog.d(TAG, "Executing click at (" + x + ", " + y + ")");
            boolean success = mController.click(x, y);

            mMemory.addActionHistory("click", x + "," + y, success ? "success" : "failed");

            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Click failed at (" + x + ", " + y + ")");
                Slog.w(TAG, "Click failed at (" + x + ", " + y + ")");
            } else {
                result.putString("message", "Clicked at (" + x + ", " + y + ")");
                Slog.d(TAG, "Click succeeded at (" + x + ", " + y + ")");
            }
            return result;
        }
    }

    /**
     * SwipeTool - 滑动工具
     *
     * <p>在屏幕上从起点滑动到终点，可指定持续时间。
     */
    public static class SwipeTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param controller 输入控制器
         * @param memory 记忆管理器
         */
        public SwipeTool(@NonNull InputController controller, @NonNull MemoryManager memory) {
            mController = controller;
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "swipe";
        }

        @Override
        public String getDescription() {
            return "Swipe from one point to another on the screen.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("x1", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Start x coordinate"))
                                .put("y1", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Start y coordinate"))
                                .put("x2", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "End x coordinate"))
                                .put("y2", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "End y coordinate"))
                                .put("duration", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Duration in milliseconds")
                                        .put("default", 300)))
                        .put("required", new JSONArray().put("x1").put("y1").put("x2").put("y2"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for swipe", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            int x1 = arguments.getInt("x1", -1);
            int y1 = arguments.getInt("y1", -1);
            int x2 = arguments.getInt("x2", -1);
            int y2 = arguments.getInt("y2", -1);
            int duration = arguments.getInt("duration", 300);

            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                Slog.w(TAG, "Invalid swipe coordinates");
                result.putString("status", "error");
                result.putString("message", "Invalid coordinates");
                return result;
            }

            Slog.d(TAG, "Executing swipe from (" + x1 + "," + y1 + ") to (" + x2 + "," + y2 + ") duration=" + duration);
            boolean success = mController.swipe(x1, y1, x2, y2, duration);

            String target = String.format("(%d,%d) -> (%d,%d) duration=%d", x1, y1, x2, y2, duration);
            mMemory.addActionHistory("swipe", target, success ? "success" : "failed");

            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Swipe failed");
                Slog.w(TAG, "Swipe failed");
            }
            return result;
        }
    }

    /**
     * InputTextTool - 文本输入工具
     *
     * <p>在当前焦点的文本框中输入文本内容。
     */
    public static class InputTextTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param controller 输入控制器
         * @param memory 记忆管理器
         */
        public InputTextTool(@NonNull InputController controller, @NonNull MemoryManager memory) {
            mController = controller;
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "input_text";
        }

        @Override
        public String getDescription() {
            return "Input text into the currently focused text field.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("text", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The text to input")))
                        .put("required", new JSONArray().put("text"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for input_text", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String text = arguments.getString("text");

            if (text == null || text.isEmpty()) {
                Slog.w(TAG, "Input text is empty");
                result.putString("status", "error");
                result.putString("message", "Text cannot be empty");
                return result;
            }

            Slog.d(TAG, "Executing input_text, length: " + text.length() + " chars");
            boolean success = mController.inputText(text);

            mMemory.addActionHistory("input_text", text.length() + " chars", success ? "success" : "failed");

            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Input text failed");
                Slog.w(TAG, "Input text failed");
            }
            return result;
        }
    }

    /**
     * LaunchAppTool - 启动应用工具
     *
     * <p>根据包名启动指定的 Android 应用。
     */
    public static class LaunchAppTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param controller 输入控制器
         * @param memory 记忆管理器
         */
        public LaunchAppTool(@NonNull InputController controller, @NonNull MemoryManager memory) {
            mController = controller;
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "launch_app";
        }

        @Override
        public String getDescription() {
            return "Launch an application by its package name.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("package_name", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The package name of the app to launch")))
                        .put("required", new JSONArray().put("package_name"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for launch_app", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String packageName = arguments.getString("package_name");

            if (packageName == null || packageName.isEmpty()) {
                Slog.w(TAG, "Package name is empty");
                result.putString("status", "error");
                result.putString("message", "Package name cannot be empty");
                return result;
            }

            Slog.d(TAG, "Launching app: " + packageName);
            boolean success = mController.launchApp(packageName);

            mMemory.addActionHistory("launch_app", packageName, success ? "success" : "failed");

            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Launch app failed: " + packageName);
                Slog.w(TAG, "Launch app failed: " + packageName);
            }
            return result;
        }
    }

    /**
     * PressBackTool - 返回键工具
     *
     * <p>模拟按下系统返回键。
     */
    public static class PressBackTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param controller 输入控制器
         * @param memory 记忆管理器
         */
        public PressBackTool(@NonNull InputController controller, @NonNull MemoryManager memory) {
            mController = controller;
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "press_back";
        }

        @Override
        public String getDescription() {
            return "Press the back button.";
        }

        @Override
        public JSONObject getInputSchema() {
            return new JSONObject();
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            Slog.d(TAG, "Pressing back button");
            boolean success = mController.pressBack();

            mMemory.addActionHistory("press_back", "system", success ? "success" : "failed");

            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Press back failed");
                Slog.w(TAG, "Press back failed");
            }
            return result;
        }
    }

    /**
     * PressHomeTool - Home键工具
     *
     * <p>模拟按下系统Home键，返回主屏幕。
     */
    public static class PressHomeTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param controller 输入控制器
         * @param memory 记忆管理器
         */
        public PressHomeTool(@NonNull InputController controller, @NonNull MemoryManager memory) {
            mController = controller;
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "press_home";
        }

        @Override
        public String getDescription() {
            return "Press the home button.";
        }

        @Override
        public JSONObject getInputSchema() {
            return new JSONObject();
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            Slog.d(TAG, "Pressing home button");
            boolean success = mController.pressHome();

            mMemory.addActionHistory("press_home", "system", success ? "success" : "failed");

            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Press home failed");
                Slog.w(TAG, "Press home failed");
            }
            return result;
        }
    }

    /**
     * RememberTool - 记忆存储工具
     *
     * <p>用于 AI 代理存储需要记住的信息，如用户偏好设置、事实或上下文信息。
     * 存储的记忆可以在后续对话中被检索使用。
     */
    public static class RememberTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param memory 记忆管理器
         */
        public RememberTool(@NonNull MemoryManager memory) {
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "remember";
        }

        @Override
        public String getDescription() {
            return "Store information in memory for future recall. " +
                    "Use this to remember user preferences, facts, or context.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("key", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The memory key"))
                                .put("value", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The value to remember"))
                                .put("category", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The memory category (e.g., 'preferences', 'facts', 'context')")
                                        .put("default", "general")))
                        .put("required", new JSONArray().put("key").put("value"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for remember", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String key = arguments.getString("key");
            String value = arguments.getString("value");
            String category = arguments.getString("category", "general");

            if (key == null || key.isEmpty()) {
                Slog.w(TAG, "Remember: key is empty");
                result.putString("status", "error");
                result.putString("message", "Key cannot be empty");
                return result;
            }

            if (value == null || value.isEmpty()) {
                Slog.w(TAG, "Remember: value is empty");
                result.putString("status", "error");
                result.putString("message", "Value cannot be empty");
                return result;
            }

            Slog.d(TAG, "remember: key=" + key + ", value=" + value + ", category=" + category);
            mMemory.remember(key, value, category);

            result.putString("status", "success");
            result.putString("message", "Remembered: " + key + " = " + value + " (category: " + category + ")");

            return result;
        }
    }

    /**
     * RecallTool - 记忆检索工具
     *
     * <p>用于检索之前存储的记忆信息。如果找到则返回值，如果未找到则返回 not_found 状态。
     * 检索操作是异步的，使用 CountDownLatch 等待最多5秒。
     */
    public static class RecallTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param memory 记忆管理器
         */
        public RecallTool(@NonNull MemoryManager memory) {
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "recall";
        }

        @Override
        public String getDescription() {
            return "Recall previously remembered information. Returns the value if found.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("key", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The memory key to recall"))
                                .put("category", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The memory category")
                                        .put("default", "general")))
                        .put("required", new JSONArray().put("key"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for recall", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            final CountDownLatch latch = new CountDownLatch(1);
            String key = arguments.getString("key");
            String category = arguments.getString("category", "general");

            if (key == null || key.isEmpty()) {
                Slog.w(TAG, "Recall: key is empty");
                result.putString("status", "error");
                result.putString("message", "Key cannot be empty");
                return result;
            }

            result.putString("key", key);
            Slog.d(TAG, "Recalling memory: key=" + key + ", category=" + category);

            mMemory.recall(key, category, new UserMemoryManager.RecallCallback() {
                @Override
                public void onResult(String value) {
                    Slog.d(TAG, "Recall found: key=" + key + ", value=" + value);
                    result.putString("status", "success");
                    result.putString("value", value);
                    result.putString("message", "Recalled: " + key + " = " + value);
                    latch.countDown();
                }

                @Override
                public void onNotFound() {
                    Slog.d(TAG, "Recall not found: key=" + key);
                    result.putString("status", "not_found");
                    result.putString("message", "No memory found for key: " + key);
                    latch.countDown();
                }
            });

            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Slog.w(TAG, "Recall interrupted: key=" + key);
                result.putString("status", "error");
                result.putString("message", "Recall interrupted");
            }

            return result;
        }
    }

    /**
     * SetPreferenceTool - 偏好设置工具
     *
     * <p>用于在特定类别中存储用户偏好设置。
     */
    public static class SetPreferenceTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param memory 记忆管理器
         */
        public SetPreferenceTool(@NonNull MemoryManager memory) {
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "set_preference";
        }

        @Override
        public String getDescription() {
            return "Set a user preference in a specific category.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("category", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The preference category (e.g., 'food', 'travel', 'app')"))
                                .put("key", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The preference key"))
                                .put("value", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The preference value")))
                        .put("required", new JSONArray().put("category").put("key").put("value"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for set_preference", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String category = arguments.getString("category");
            String key = arguments.getString("key");
            String value = arguments.getString("value");

            if (category == null || category.isEmpty()) {
                Slog.w(TAG, "SetPreference: category is empty");
                result.putString("status", "error");
                result.putString("message", "Category cannot be empty");
                return result;
            }

            if (key == null || key.isEmpty()) {
                Slog.w(TAG, "SetPreference: key is empty");
                result.putString("status", "error");
                result.putString("message", "Key cannot be empty");
                return result;
            }

            if (value == null || value.isEmpty()) {
                Slog.w(TAG, "SetPreference: value is empty");
                result.putString("status", "error");
                result.putString("message", "Value cannot be empty");
                return result;
            }

            Slog.d(TAG, "set_preference: " + category + "." + key + " = " + value);
            mMemory.setPreference(category, key, value);

            result.putString("status", "success");
            result.putString("message", "Set preference: " + category + "." + key + " = " + value);

            return result;
        }
    }

    /**
     * GetPreferenceTool - 偏好获取工具
     *
     * <p>用于从特定类别中获取用户偏好设置。
     * 检索操作是异步的，使用 CountDownLatch 等待最多5秒。
     */
    public static class GetPreferenceTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param memory 记忆管理器
         */
        public GetPreferenceTool(@NonNull MemoryManager memory) {
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "get_preference";
        }

        @Override
        public String getDescription() {
            return "Get a user preference from a specific category.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("category", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The preference category"))
                                .put("key", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The preference key")))
                        .put("required", new JSONArray().put("category").put("key"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for get_preference", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            final CountDownLatch latch = new CountDownLatch(1);
            String category = arguments.getString("category");
            String key = arguments.getString("key");

            if (category == null || category.isEmpty()) {
                Slog.w(TAG, "GetPreference: category is empty");
                result.putString("status", "error");
                result.putString("message", "Category cannot be empty");
                return result;
            }

            if (key == null || key.isEmpty()) {
                Slog.w(TAG, "GetPreference: key is empty");
                result.putString("status", "error");
                result.putString("message", "Key cannot be empty");
                return result;
            }

            Slog.d(TAG, "Getting preference: " + category + "." + key);
            mMemory.getPreference(category, key, new UserMemoryManager.RecallCallback() {
                @Override
                public void onResult(String value) {
                    Slog.d(TAG, "GetPreference found: " + category + "." + key + " = " + value);
                    result.putString("status", "success");
                    result.putString("value", value);
                    result.putString("message", category + "." + key + " = " + value);
                    latch.countDown();
                }

                @Override
                public void onNotFound() {
                    Slog.d(TAG, "GetPreference not found: " + category + "." + key);
                    result.putString("status", "not_found");
                    result.putString("message", "Preference not found: " + category + "." + key);
                    latch.countDown();
                }
            });

            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Slog.w(TAG, "GetPreference interrupted");
                result.putString("status", "error");
                result.putString("message", "Get preference interrupted");
            }

            return result;
        }
    }

    /**
     * ScheduleTaskTool - 定时任务工具
     *
     * <p>用于调度一次性任务，在指定的时间戳执行。
     */
    public static class ScheduleTaskTool implements MiniMaxClient.AgentTool {
        private final TaskSchedulerInterface mScheduler;

        /**
         * 构造方法
         * @param scheduler 任务调度器
         */
        public ScheduleTaskTool(@NonNull TaskSchedulerInterface scheduler) {
            mScheduler = scheduler;
        }

        @Override
        public String getName() {
            return "schedule_task";
        }

        @Override
        public String getDescription() {
            return "Schedule a task to be executed at a specific time (timestamp in milliseconds since epoch). " +
                    "Use this for one-time delayed tasks.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("timestamp", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Execution time as Unix timestamp in milliseconds"))
                                .put("task", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "Description of the task to execute")))
                        .put("required", new JSONArray().put("timestamp").put("task"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for schedule_task", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            long timestamp = arguments.getLong("timestamp", 0);
            String task = arguments.getString("task");

            if (timestamp <= 0) {
                Slog.w(TAG, "ScheduleTask: invalid timestamp: " + timestamp);
                result.putString("status", "error");
                result.putString("message", "Invalid timestamp: " + timestamp);
                return result;
            }

            if (task == null || task.isEmpty()) {
                Slog.w(TAG, "ScheduleTask: task description is empty");
                result.putString("status", "error");
                result.putString("message", "Task description cannot be empty");
                return result;
            }

            Slog.d(TAG, "schedule_task: task=" + task + ", timestamp=" + timestamp);
            String taskId = mScheduler.scheduleTask(task, timestamp);

            result.putString("status", "success");
            result.putString("task_id", taskId);
            result.putString("message", "Task scheduled: " + taskId + " for " + timestamp);

            return result;
        }
    }

    /**
     * ScheduleRecurringTool - 周期任务工具
     *
     * <p>用于调度周期性重复执行的任务，如定期检查通知等。
     */
    public static class ScheduleRecurringTool implements MiniMaxClient.AgentTool {
        private final TaskSchedulerInterface mScheduler;

        /**
         * 构造方法
         * @param scheduler 任务调度器
         */
        public ScheduleRecurringTool(@NonNull TaskSchedulerInterface scheduler) {
            mScheduler = scheduler;
        }

        @Override
        public String getName() {
            return "schedule_recurring";
        }

        @Override
        public String getDescription() {
            return "Schedule a recurring task that executes at regular intervals (in seconds). " +
                    "Use this for periodic tasks like checking notifications.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("interval_seconds", new JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Interval between executions in seconds"))
                                .put("task", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "Description of the recurring task")))
                        .put("required", new JSONArray().put("interval_seconds").put("task"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for schedule_recurring", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            int intervalSeconds = arguments.getInt("interval_seconds", 0);
            String task = arguments.getString("task");

            if (intervalSeconds <= 0) {
                Slog.w(TAG, "ScheduleRecurring: invalid interval: " + intervalSeconds);
                result.putString("status", "error");
                result.putString("message", "Invalid interval: " + intervalSeconds);
                return result;
            }

            if (task == null || task.isEmpty()) {
                Slog.w(TAG, "ScheduleRecurring: task description is empty");
                result.putString("status", "error");
                result.putString("message", "Task description cannot be empty");
                return result;
            }

            long intervalMillis = intervalSeconds * 1000L;
            Slog.d(TAG, "schedule_recurring: task=" + task + ", interval=" + intervalSeconds + "s");
            String taskId = mScheduler.scheduleRecurringTask(task, intervalMillis);

            result.putString("status", "success");
            result.putString("task_id", taskId);
            result.putString("message", "Recurring task scheduled: " + taskId + " every " + intervalSeconds + "s");

            return result;
        }
    }

    /**
     * CancelTaskTool - 取消任务工具
     *
     * <p>用于取消之前调度的任务。
     */
    public static class CancelTaskTool implements MiniMaxClient.AgentTool {
        private final TaskSchedulerInterface mScheduler;

        /**
         * 构造方法
         * @param scheduler 任务调度器
         */
        public CancelTaskTool(@NonNull TaskSchedulerInterface scheduler) {
            mScheduler = scheduler;
        }

        @Override
        public String getName() {
            return "cancel_task";
        }

        @Override
        public String getDescription() {
            return "Cancel a previously scheduled task.";
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                return new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("task_id", new JSONObject()
                                        .put("type", "string")
                                        .put("description", "The task ID to cancel")))
                        .put("required", new JSONArray().put("task_id"));
            } catch (JSONException e) {
                Slog.e(TAG, "Error building input schema for cancel_task", e);
                return new JSONObject();
            }
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String taskId = arguments.getString("task_id");

            if (taskId == null || taskId.isEmpty()) {
                Slog.w(TAG, "CancelTask: task_id is empty");
                result.putString("status", "error");
                result.putString("message", "Task ID cannot be empty");
                return result;
            }

            Slog.d(TAG, "cancel_task: id=" + taskId);
            boolean cancelled = mScheduler.cancelTask(taskId);

            result.putString("status", cancelled ? "success" : "error");
            result.putString("message", cancelled ? "Task cancelled: " + taskId : "Task not found: " + taskId);

            return result;
        }
    }

    /**
     * GetPendingTasksTool - 待执行任务查询工具
     *
     * <p>用于查询当前待执行的调度任务数量。
     */
    public static class GetPendingTasksTool implements MiniMaxClient.AgentTool {
        private final TaskSchedulerInterface mScheduler;

        /**
         * 构造方法
         * @param scheduler 任务调度器
         */
        public GetPendingTasksTool(@NonNull TaskSchedulerInterface scheduler) {
            mScheduler = scheduler;
        }

        @Override
        public String getName() {
            return "get_pending_tasks";
        }

        @Override
        public String getDescription() {
            return "Get the count of pending scheduled tasks.";
        }

        @Override
        public JSONObject getInputSchema() {
            return new JSONObject();
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            int count = mScheduler.getPendingTaskCount();

            Slog.d(TAG, "get_pending_tasks: count=" + count);
            result.putString("status", "success");
            result.putInt("count", count);
            result.putString("message", count + " pending tasks");

            return result;
        }
    }

    /**
     * GetContextTool - 上下文获取工具
     *
     * <p>用于获取 AI 代理的当前上下文信息，包括：
     * <ul>
     *   <li>最近访问的屏幕历史</li>
     *   <li>最近执行的动作历史</li>
     *   <li>对话历史记录</li>
     * </ul>
     *
     * <p>这是一个异步操作，使用 CountDownLatch 等待最多5秒。
     */
    public static class GetContextTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

        /**
         * 构造方法
         * @param memory 记忆管理器
         */
        public GetContextTool(@NonNull MemoryManager memory) {
            mMemory = memory;
        }

        @Override
        public String getName() {
            return "get_context";
        }

        @Override
        public String getDescription() {
            return "Get recent screen history, action history, and conversation context " +
                    "to understand what has been happening.";
        }

        @Override
        public JSONObject getInputSchema() {
            return new JSONObject();
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            final CountDownLatch latch = new CountDownLatch(1);

            Slog.d(TAG, "Getting context information...");
            final StringBuilder context = new StringBuilder();
            context.append("=== Recent Context ===\n\n");

            mMemory.getScreenHistory(screenHistory -> {
                context.append("--- Recent Screens ---\n");
                for (int i = 0; i < screenHistory.size(); i++) {
                    context.append(screenHistory.get(i).description).append("\n");
                }
                context.append("\n");

                mMemory.getActionHistory(actionHistory -> {
                    context.append("--- Recent Actions ---\n");
                    for (int i = 0; i < actionHistory.size(); i++) {
                        UserMemoryManager.ActionRecord record = actionHistory.get(i);
                        context.append(String.format("[%s] %s -> %s (%s)\n",
                                record.action, record.target, record.result, record.timestamp));
                    }
                    context.append("\n");

                    mMemory.getConversationHistory(conversationHistory -> {
                        context.append("--- Conversation History ---\n");
                        for (int i = 0; i < conversationHistory.size(); i++) {
                            UserMemoryManager.ConversationTurn turn = conversationHistory.get(i);
                            context.append(String.format("[%s] %s\n", turn.role, turn.content));
                        }

                        Slog.d(TAG, "Context retrieved successfully");
                        result.putString("context", context.toString());
                        result.putString("status", "success");
                        result.putString("message", "Context retrieved");
                        latch.countDown();
                    });
                });
            });

            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Slog.w(TAG, "Context retrieval interrupted");
                result.putString("status", "error");
                result.putString("message", "Context retrieval interrupted");
            }

            return result;
        }
    }
}
