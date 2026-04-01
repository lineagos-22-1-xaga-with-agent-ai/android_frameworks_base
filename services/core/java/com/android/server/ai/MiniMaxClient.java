package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.UiThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class MiniMaxClient {
    private static final String TAG = "MiniMaxClient";
    private static final String BASE_URL = "https://api.minimaxi.com/anthropic/v1/messages";

    private static final int MAX_TOKENS = 4096;
    private static final int TIMEOUT_MS = 30000;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private String mApiKey;

    private final Map<String, AgentTool> mTools = new ConcurrentHashMap<>();
    private Executor mCallbackExecutor = UiThread.getExecutor();

    public interface AgentTool {
        String getName();
        String getDescription();
        JSONObject getInputSchema();
        Bundle execute(Bundle arguments) throws Exception;
    }

    public interface Callback {
        void onThinking(String thinking);
        void onText(String text);
        void onToolCall(String toolName, Bundle arguments);
        void onComplete();
        void onError(String error);
    }

    public MiniMaxClient() {}

    public void setApiKey(String apiKey) {
        synchronized (mLock) {
            mApiKey = apiKey;
        }
    }

    @Nullable
    public String getApiKey() {
        synchronized (mLock) {
            return mApiKey;
        }
    }

    public void registerTool(AgentTool tool) {
        mTools.put(tool.getName(), tool);
    }

    public void unregisterTool(String toolName) {
        mTools.remove(toolName);
    }

    public void sendMessage(@NonNull String message, @Nullable byte[] screenshot,
            @NonNull Callback callback) {
        mCallbackExecutor.execute(() -> sendMessageInternal(message, screenshot, callback));
    }

    private void sendMessageInternal(@NonNull String message, @Nullable byte[] screenshot,
            @NonNull Callback callback) {
        String apiKey;
        synchronized (mLock) {
            apiKey = mApiKey;
        }

        if (apiKey == null || apiKey.isEmpty()) {
            callback.onError("API key not configured");
            return;
        }

        try {
            JSONObject requestBody = buildRequestBody(message, screenshot);
            String response = sendHttpRequest(requestBody.toString(), apiKey);
            parseResponse(response, callback);
        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
            callback.onError(e.getMessage());
        }
    }

    private JSONObject buildRequestBody(String message, byte[] screenshot) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", "MiniMax-M2.7");
        body.put("max_tokens", MAX_TOKENS);
        body.put("stream", true);

        JSONArray messages = new JSONArray();

        if (screenshot != null) {
            String base64Image = Base64.encodeToString(screenshot, Base64.NO_WRAP);
            JSONObject imageContent = new JSONObject();
            imageContent.put("type", "image");
            imageContent.put("source", new JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/png")
                    .put("data", base64Image));

            JSONObject userMsgWithImage = new JSONObject();
            userMsgWithImage.put("role", "user");
            userMsgWithImage.put("content", new JSONArray()
                    .put(imageContent)
                    .put(new JSONObject().put("type", "text").put("text", message)));
            messages.put(userMsgWithImage);
        } else {
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", message);
            messages.put(userMsg);
        }

        body.put("messages", messages);

        if (!mTools.isEmpty()) {
            body.put("tools", buildToolsArray());
        }

        return body;
    }

    private JSONArray buildToolsArray() throws JSONException {
        JSONArray tools = new JSONArray();
        for (AgentTool tool : mTools.values()) {
            JSONObject toolObj = new JSONObject();
            toolObj.put("name", tool.getName());
            toolObj.put("description", tool.getDescription());
            toolObj.put("input_schema", tool.getInputSchema());
            tools.put(toolObj);
        }
        return tools;
    }

    private String sendHttpRequest(String requestBody, String apiKey) throws IOException {
        URL url = new URL(BASE_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorBody = readStream(conn.getErrorStream());
            throw new IOException("HTTP " + responseCode + ": " + errorBody);
        }

        return readStream(conn.getInputStream());
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            result.write(buffer, 0, bytesRead);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }

    private void parseResponse(String response, Callback callback) throws JSONException {
        String[] lines = response.split("\n");

        for (String line : lines) {
            if (line.startsWith("data: ")) {
                String data = line.substring(6);
                if (data.equals("[DONE]")) {
                    callback.onComplete();
                    return;
                }

                JSONObject json = new JSONObject(data);
                String type = json.optString("type");

                if ("content_block_start".equals(type)) {
                    JSONObject contentBlock = json.optJSONObject("content_block");
                    if (contentBlock != null && "text".equals(contentBlock.optString("type"))) {
                    }
                } else if ("content_block_delta".equals(type)) {
                    JSONObject delta = json.optJSONObject("delta");
                    if (delta != null) {
                        String deltaType = delta.optString("type");
                        if ("thinking_delta".equals(deltaType)) {
                            callback.onThinking(delta.optString("thinking", ""));
                        } else if ("text_delta".equals(deltaType)) {
                            callback.onText(delta.optString("text", ""));
                        } else if ("tool_use_delta".equals(deltaType)) {
                            String toolName = delta.optString("name", "");
                            String toolInput = delta.optString("input", "");
                            try {
                                Bundle args = jsonToBundle(new JSONObject(toolInput));
                                callback.onToolCall(toolName, args);
                            } catch (JSONException e) {
                                Log.w(TAG, "Failed to parse tool input", e);
                            }
                        }
                    }
                } else if ("message_delta".equals(type)) {
                    JSONObject delta = json.optJSONObject("delta");
                    if (delta != null && delta.has("stop_reason")) {
                    }
                }
            }
        }

        callback.onComplete();
    }

    private Bundle jsonToBundle(JSONObject json) throws JSONException {
        Bundle bundle = new Bundle();
        for (String key : json.keySet()) {
            Object value = json.get(key);
            if (value instanceof String) {
                bundle.putString(key, (String) value);
            } else if (value instanceof Integer) {
                bundle.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                bundle.putLong(key, (Long) value);
            } else if (value instanceof Double) {
                bundle.putDouble(key, (Double) value);
            } else if (value instanceof Boolean) {
                bundle.putBoolean(key, (Boolean) value);
            } else if (value instanceof JSONObject) {
                bundle.putBundle(key, jsonToBundle((JSONObject) value));
            } else if (value instanceof JSONArray) {
                bundle.putString(key, value.toString());
            } else {
                bundle.putString(key, String.valueOf(value));
            }
        }
        return bundle;
    }

    public static byte[] bitmapToPng(Bitmap bitmap) {
        if (bitmap == null) return null;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return stream.toByteArray();
    }
}
