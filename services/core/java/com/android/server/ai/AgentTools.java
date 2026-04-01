package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AgentTools {

    private static final String TAG = "AgentTools";

    public interface ScreenshotProvider {
        Bitmap takeScreenshot();
    }

    public interface InputController {
        boolean click(int x, int y);
        boolean swipe(int x1, int y1, int x2, int y2, int duration);
        boolean inputText(String text);
        boolean pressBack();
        boolean pressHome();
        boolean launchApp(String packageName);
    }

    public interface MemoryManager {
        void remember(String key, String value, String category);
        void recall(String key, String category, UserMemoryManager.RecallCallback callback);
        void setPreference(String category, String key, String value);
        void getPreference(String category, String key, UserMemoryManager.RecallCallback callback);
        void getScreenHistory(UserMemoryManager.ScreenHistoryCallback callback);
        void getActionHistory(UserMemoryManager.ActionHistoryCallback callback);
        void getConversationHistory(UserMemoryManager.ConversationHistoryCallback callback);
        void addScreenHistory(String description, byte[] screenshotHash);
        void addActionHistory(String action, String target, String result);
        void addConversationTurn(String role, String content);
    }

    public interface TaskSchedulerInterface {
        String scheduleTask(String description, long triggerAtMillis);
        String scheduleRecurringTask(String description, long intervalMillis);
        boolean cancelTask(String taskId);
        int getPendingTaskCount();
    }

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
                Log.e(TAG, "Error taking screenshot", e);
                result.putString("status", "error");
                result.putString("message", e.getMessage());
            }
            return result;
        }
    }

    public static class ClickTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

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
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            int x = arguments.getInt("x", -1);
            int y = arguments.getInt("y", -1);

            if (x < 0 || y < 0) {
                result.putString("status", "error");
                result.putString("message", "Invalid coordinates: x=" + x + ", y=" + y);
                return result;
            }

            boolean success = mController.click(x, y);
            
            mMemory.addActionHistory("click", x + "," + y, success ? "success" : "failed");
            
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Click failed at (" + x + ", " + y + ")");
            } else {
                result.putString("message", "Clicked at (" + x + ", " + y + ")");
            }
            return result;
        }
    }

    public static class SwipeTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

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
                result.putString("status", "error");
                result.putString("message", "Invalid coordinates");
                return result;
            }

            boolean success = mController.swipe(x1, y1, x2, y2, duration);
            
            String target = String.format("(%d,%d) -> (%d,%d) duration=%d", x1, y1, x2, y2, duration);
            mMemory.addActionHistory("swipe", target, success ? "success" : "failed");
            
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Swipe failed");
            }
            return result;
        }
    }

    public static class InputTextTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

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
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("text", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "The text to input")))
                    .put("required", new JSONArray().put("text"));
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String text = arguments.getString("text");

            if (text == null || text.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Text cannot be empty");
                return result;
            }

            boolean success = mController.inputText(text);
            
            mMemory.addActionHistory("input_text", text.length() + " chars", success ? "success" : "failed");
            
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Input text failed");
            }
            return result;
        }
    }

    public static class LaunchAppTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

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
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("package_name", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "The package name of the app to launch")))
                    .put("required", new JSONArray().put("package_name"));
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String packageName = arguments.getString("package_name");

            if (packageName == null || packageName.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Package name cannot be empty");
                return result;
            }

            boolean success = mController.launchApp(packageName);
            
            mMemory.addActionHistory("launch_app", packageName, success ? "success" : "failed");
            
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Launch app failed: " + packageName);
            }
            return result;
        }
    }

    public static class PressBackTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

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
            boolean success = mController.pressBack();
            
            mMemory.addActionHistory("press_back", "system", success ? "success" : "failed");
            
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Press back failed");
            }
            return result;
        }
    }

    public static class PressHomeTool implements MiniMaxClient.AgentTool {
        private final InputController mController;
        private final MemoryManager mMemory;

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
            boolean success = mController.pressHome();
            
            mMemory.addActionHistory("press_home", "system", success ? "success" : "failed");
            
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Press home failed");
            }
            return result;
        }
    }

    public static class RememberTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

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
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String key = arguments.getString("key");
            String value = arguments.getString("value");
            String category = arguments.getString("category", "general");

            if (key == null || key.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Key cannot be empty");
                return result;
            }

            if (value == null || value.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Value cannot be empty");
                return result;
            }

            mMemory.remember(key, value, category);
            
            result.putString("status", "success");
            result.putString("message", "Remembered: " + key + " = " + value + " (category: " + category + ")");
            
            Log.d(TAG, "remember: key=" + key + ", value=" + value + ", category=" + category);
            return result;
        }
    }

    public static class RecallTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

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
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String key = arguments.getString("key");
            String category = arguments.getString("category", "general");

            if (key == null || key.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Key cannot be empty");
                return result;
            }

            result.putString("status", "pending");
            result.putString("key", key);

            mMemory.recall(key, category, new UserMemoryManager.RecallCallback() {
                @Override
                public void onResult(String value) {
                    result.putString("status", "success");
                    result.putString("value", value);
                    result.putString("message", "Recalled: " + key + " = " + value);
                }

                @Override
                public void onNotFound() {
                    result.putString("status", "not_found");
                    result.putString("message", "No memory found for key: " + key);
                }
            });

            return result;
        }
    }

    public static class SetPreferenceTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

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
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String category = arguments.getString("category");
            String key = arguments.getString("key");
            String value = arguments.getString("value");

            if (category == null || category.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Category cannot be empty");
                return result;
            }

            if (key == null || key.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Key cannot be empty");
                return result;
            }

            if (value == null || value.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Value cannot be empty");
                return result;
            }

            mMemory.setPreference(category, key, value);
            
            result.putString("status", "success");
            result.putString("message", "Set preference: " + category + "." + key + " = " + value);
            
            Log.d(TAG, "set_preference: " + category + "." + key + " = " + value);
            return result;
        }
    }

    public static class GetPreferenceTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

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
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String category = arguments.getString("category");
            String key = arguments.getString("key");

            if (category == null || category.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Category cannot be empty");
                return result;
            }

            if (key == null || key.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Key cannot be empty");
                return result;
            }

            result.putString("status", "pending");

            mMemory.getPreference(category, key, new UserMemoryManager.RecallCallback() {
                @Override
                public void onResult(String value) {
                    result.putString("status", "success");
                    result.putString("value", value);
                    result.putString("message", category + "." + key + " = " + value);
                }

                @Override
                public void onNotFound() {
                    result.putString("status", "not_found");
                    result.putString("message", "Preference not found: " + category + "." + key);
                }
            });

            return result;
        }
    }

    public static class ScheduleTaskTool implements MiniMaxClient.AgentTool {
        private final TaskSchedulerInterface mScheduler;

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
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            long timestamp = arguments.getLong("timestamp", 0);
            String task = arguments.getString("task");

            if (timestamp <= 0) {
                result.putString("status", "error");
                result.putString("message", "Invalid timestamp: " + timestamp);
                return result;
            }

            if (task == null || task.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Task description cannot be empty");
                return result;
            }

            String taskId = mScheduler.scheduleTask(task, timestamp);
            
            result.putString("status", "success");
            result.putString("task_id", taskId);
            result.putString("message", "Task scheduled: " + taskId + " for " + timestamp);
            
            Log.d(TAG, "schedule_task: id=" + taskId + ", task=" + task + ", timestamp=" + timestamp);
            return result;
        }
    }

    public static class ScheduleRecurringTool implements MiniMaxClient.AgentTool {
        private final TaskSchedulerInterface mScheduler;

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
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            int intervalSeconds = arguments.getInt("interval_seconds", 0);
            String task = arguments.getString("task");

            if (intervalSeconds <= 0) {
                result.putString("status", "error");
                result.putString("message", "Invalid interval: " + intervalSeconds);
                return result;
            }

            if (task == null || task.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Task description cannot be empty");
                return result;
            }

            long intervalMillis = intervalSeconds * 1000L;
            String taskId = mScheduler.scheduleRecurringTask(task, intervalMillis);
            
            result.putString("status", "success");
            result.putString("task_id", taskId);
            result.putString("message", "Recurring task scheduled: " + taskId + " every " + intervalSeconds + "s");
            
            Log.d(TAG, "schedule_recurring: id=" + taskId + ", task=" + task + ", interval=" + intervalSeconds + "s");
            return result;
        }
    }

    public static class CancelTaskTool implements MiniMaxClient.AgentTool {
        private final TaskSchedulerInterface mScheduler;

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
            return new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("task_id", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "The task ID to cancel")))
                    .put("required", new JSONArray().put("task_id"));
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            String taskId = arguments.getString("task_id");

            if (taskId == null || taskId.isEmpty()) {
                result.putString("status", "error");
                result.putString("message", "Task ID cannot be empty");
                return result;
            }

            boolean cancelled = mScheduler.cancelTask(taskId);
            
            result.putString("status", cancelled ? "success" : "error");
            result.putString("message", cancelled ? "Task cancelled: " + taskId : "Task not found: " + taskId);
            
            Log.d(TAG, "cancel_task: id=" + taskId + ", cancelled=" + cancelled);
            return result;
        }
    }

    public static class GetPendingTasksTool implements MiniMaxClient.AgentTool {
        private final TaskSchedulerInterface mScheduler;

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
            
            result.putString("status", "success");
            result.putInt("count", count);
            result.putString("message", count + " pending tasks");
            
            return result;
        }
    }

    public static class GetContextTool implements MiniMaxClient.AgentTool {
        private final MemoryManager mMemory;

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
            result.putString("status", "success");

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

                        result.putString("context", context.toString());
                        result.putString("message", "Context retrieved");
                    });
                });
            });

            return result;
        }
    }
}
