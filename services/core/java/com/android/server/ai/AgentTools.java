package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

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

    public interface ElementInfoProvider {
        String getElementInfo();
    }

    public static class ScreenshotTool implements MiniMaxClient.AgentTool {
        private final ScreenshotProvider mProvider;

        public ScreenshotTool(@NonNull ScreenshotProvider provider) {
            mProvider = provider;
        }

        @Override
        public String getName() {
            return "screenshot";
        }

        @Override
        public String getDescription() {
            return "Take a screenshot of the current screen. Returns the screenshot as a bitmap.";
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
                    result.putString("status", "success");
                    result.putInt("width", bitmap.getWidth());
                    result.putInt("height", bitmap.getHeight());
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

        public ClickTool(@NonNull InputController controller) {
            mController = controller;
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
                    .put("required", new org.json.JSONArray().put("x").put("y"));
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            int x = arguments.getInt("x", -1);
            int y = arguments.getInt("y", -1);

            if (x < 0 || y < 0) {
                result.putString("status", "error");
                result.putString("message", "Invalid coordinates");
                return result;
            }

            boolean success = mController.click(x, y);
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Click failed");
            }
            return result;
        }
    }

    public static class SwipeTool implements MiniMaxClient.AgentTool {
        private final InputController mController;

        public SwipeTool(@NonNull InputController controller) {
            mController = controller;
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
                    .put("required", new org.json.JSONArray().put("x1").put("y1").put("x2").put("y2"));
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
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Swipe failed");
            }
            return result;
        }
    }

    public static class InputTextTool implements MiniMaxClient.AgentTool {
        private final InputController mController;

        public InputTextTool(@NonNull InputController controller) {
            mController = controller;
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
                    .put("required", new org.json.JSONArray().put("text"));
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
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Input text failed");
            }
            return result;
        }
    }

    public static class LaunchAppTool implements MiniMaxClient.AgentTool {
        private final InputController mController;

        public LaunchAppTool(@NonNull InputController controller) {
            mController = controller;
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
                    .put("required", new org.json.JSONArray().put("package_name"));
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
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Launch app failed");
            }
            return result;
        }
    }

    public static class PressBackTool implements MiniMaxClient.AgentTool {
        private final InputController mController;

        public PressBackTool(@NonNull InputController controller) {
            mController = controller;
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
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Press back failed");
            }
            return result;
        }
    }

    public static class PressHomeTool implements MiniMaxClient.AgentTool {
        private final InputController mController;

        public PressHomeTool(@NonNull InputController controller) {
            mController = controller;
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
            result.putString("status", success ? "success" : "error");
            if (!success) {
                result.putString("message", "Press home failed");
            }
            return result;
        }
    }

    public static class GetElementInfoTool implements MiniMaxClient.AgentTool {
        private final ElementInfoProvider mProvider;

        public GetElementInfoTool(@NonNull ElementInfoProvider provider) {
            mProvider = provider;
        }

        @Override
        public String getName() {
            return "get_element_info";
        }

        @Override
        public String getDescription() {
            return "Get information about the current screen elements for accessibility.";
        }

        @Override
        public JSONObject getInputSchema() {
            return new JSONObject();
        }

        @Override
        public Bundle execute(Bundle arguments) {
            Bundle result = new Bundle();
            try {
                String elementInfo = mProvider.getElementInfo();
                result.putString("status", "success");
                result.putString("element_info", elementInfo);
            } catch (Exception e) {
                Log.e(TAG, "Error getting element info", e);
                result.putString("status", "error");
                result.putString("message", e.getMessage());
            }
            return result;
        }
    }
}
