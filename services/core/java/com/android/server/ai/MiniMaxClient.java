package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

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
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * MiniMaxClient - MiniMax API 客户端，用于与 MiniMax AI 服务通信
 *
 * <p>该类负责：
 * <ul>
 *   <li>管理与 MiniMax API 的 HTTP 连接</li>
 *   <li>处理请求体的构建和响应解析</li>
 *   <li>支持流式响应处理（thinking、text、tool_call）</li>
 *   <li>管理工具（Tool）的注册和调用</li>
 * </ul>
 *
 * @see AgentTool
 * @see Callback
 */
public class MiniMaxClient {
    private static final String TAG = "MiniMaxClient";
    private static final String BASE_URL = "https://api.minimaxi.com/anthropic/v1/messages";

    private static final int MAX_TOKENS = 4096;
    private static final int TIMEOUT_MS = 30000;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private String mApiKey;

    private final Map<String, AgentTool> mTools = new ConcurrentHashMap<>();
    private final Executor mNetworkExecutor = Executors.newFixedThreadPool(2);
    private Executor mCallbackExecutor = command -> mNetworkExecutor.execute(() -> {
        try {
            command.run();
        } catch (Exception e) {
            Slog.e(TAG, "Background execution error", e);
        }
    });

    /**
     * AgentTool - AI 代理工具接口
     *
     * <p>每个工具都有名称、描述、输入模式定义，以及具体的执行逻辑。
     * 工具可以被 AI 模型调用来执行特定操作，如截图、点击、输入文本等。
     */
    public interface AgentTool {
        /** 获取工具名称 */
        String getName();
        /** 获取工具描述，用于 AI 模型理解工具用途 */
        String getDescription();
        /** 获取输入参数的 JSON Schema 定义 */
        JSONObject getInputSchema();
        /**
         * 执行工具操作
         * @param arguments 包含工具输入参数的 Bundle
         * @return 执行结果
         * @throws Exception 执行过程中可能发生的异常
         */
        Bundle execute(Bundle arguments) throws Exception;
    }

    /**
     * Callback - 消息处理回调接口
     *
     * <p>用于处理 MiniMax API 的流式响应，包括：
     * <ul>
     *   <li>thinking - AI 思考过程</li>
     *   <li>text - AI 生成的文本回复</li>
     *   <li>tool_call - AI 请求调用工具</li>
     *   <li>complete/error - 请求完成或出错</li>
     * </ul>
     */
    public interface Callback {
        /** AI 思考过程回调（内部推理） */
        void onThinking(String thinking);
        /** AI 文本回复回调 */
        void onText(String text);
        /** AI 请求调用工具的回调 */
        void onToolCall(String toolName, Bundle arguments);
        /** 流式响应完成回调 */
        void onComplete();
        /** 请求出错回调 */
        void onError(String error);
    }

    /** 构造方法，创建一个新的 MiniMaxClient 实例 */
    public MiniMaxClient() {}

    /**
     * 设置 API 密钥
     * @param apiKey MiniMax API 密钥
     */
    public void setApiKey(String apiKey) {
        synchronized (mLock) {
            mApiKey = apiKey;
        }
        Slog.i(TAG, "API key has been set");
    }

    /**
     * 获取当前 API 密钥
     * @return API 密钥，如果未设置则返回 null
     */
    @Nullable
    public String getApiKey() {
        synchronized (mLock) {
            return mApiKey;
        }
    }

    /**
     * 注册一个 AI 代理工具
     * @param tool 要注册的工具实例
     */
    public void registerTool(AgentTool tool) {
        mTools.put(tool.getName(), tool);
        Slog.d(TAG, "Tool registered: " + tool.getName());
    }

    /**
     * 注销一个 AI 代理工具
     * @param toolName 要注销的工具名称
     */
    public void unregisterTool(String toolName) {
        mTools.remove(toolName);
        Slog.d(TAG, "Tool unregistered: " + toolName);
    }

    /**
     * 发送消息到 MiniMax API
     *
     * <p>此方法异步执行，会在内部构建请求体、发送 HTTP 请求并解析响应。
     * 支持流式响应处理，通过 Callback 回调逐步返回结果。
     *
     * @param message 用户消息内容
     * @param screenshot 可选的屏幕截图（Base64 编码）
     * @param callback 结果回调接口
     */
    public void sendMessage(@NonNull String message, @Nullable byte[] screenshot,
            @NonNull Callback callback) {
        Slog.d(TAG, "sendMessage called, message length: " + message.length() + ", has screenshot: " + (screenshot != null));
        mCallbackExecutor.execute(() -> sendMessageInternal(message, screenshot, callback));
    }

    /**
     * 内部消息发送方法（异步执行）
     *
     * <p>此方法在内部线程池中执行，负责：
     * <ul>
     *   <li>获取 API 密钥</li>
     *   <li>构建请求体 JSON</li>
     *   <li>发送 HTTP 请求</li>
     *   <li>解析流式响应</li>
     * </ul>
     */
    private void sendMessageInternal(@NonNull String message, @Nullable byte[] screenshot,
            @NonNull Callback callback) {
        String apiKey;
        synchronized (mLock) {
            apiKey = mApiKey;
        }

        // 检查 API 密钥是否配置
        if (apiKey == null || apiKey.isEmpty()) {
            Slog.w(TAG, "API key not configured");
            callback.onError("API key not configured");
            return;
        }

        try {
            Slog.d(TAG, "Building request body...");
            JSONObject requestBody = buildRequestBody(message, screenshot);
            Slog.d(TAG, "Sending HTTP request to MiniMax API...");
            String response = sendHttpRequest(requestBody.toString(), apiKey);
            Slog.d(TAG, "Parsing response...");
            parseResponse(response, callback);
            Slog.i(TAG, "Message sent successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Error sending message: " + e.getMessage(), e);
            callback.onError(e.getMessage());
        }
    }

    /**
     * 构建请求体 JSON
     *
     * <p>根据用户消息和截图构建 MiniMax API 请求格式。
     * 如果提供了截图，则将截图 Base64 编码后包含在请求中。
     * 如果已注册工具，则同时包含工具定义。
     *
     * @param message 用户消息
     * @param screenshot 可选的屏幕截图字节数组
     * @return 构建好的请求体 JSONObject
     * @throws JSONException JSON 解析异常
     */
    private JSONObject buildRequestBody(String message, byte[] screenshot) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", "MiniMax-M2.7");
        body.put("max_tokens", MAX_TOKENS);
        body.put("stream", true);

        JSONArray messages = new JSONArray();

        if (screenshot != null) {
            // 有截图，构建带图片的消息
            Slog.d(TAG, "Building request with screenshot, size: " + screenshot.length + " bytes");
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
            // 无截图，构建纯文本消息
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", message);
            messages.put(userMsg);
        }

        body.put("messages", messages);

        // 如果有注册工具，添加到请求中
        if (!mTools.isEmpty()) {
            Slog.d(TAG, "Adding " + mTools.size() + " tools to request");
            body.put("tools", buildToolsArray());
        }

        return body;
    }

    /**
     * 构建工具数组
     *
     * <p>将所有已注册的 AgentTool 转换为 MiniMax API 格式的 JSON 数组。
     *
     * @return 工具定义的 JSON 数组
     * @throws JSONException JSON 解析异常
     */
    private JSONArray buildToolsArray() throws JSONException {
        JSONArray tools = new JSONArray();
        for (AgentTool tool : mTools.values()) {
            JSONObject toolObj = new JSONObject();
            toolObj.put("name", tool.getName());
            toolObj.put("description", tool.getDescription());
            toolObj.put("input_schema", tool.getInputSchema());
            tools.put(toolObj);
        }
        Slog.d(TAG, "Built tools array with " + tools.length() + " tools");
        return tools;
    }

    /**
     * 发送 HTTP POST 请求到 MiniMax API
     *
     * <p>此方法执行同步 HTTP 请求，包含以下步骤：
     * <ul>
     *   <li>打开 HTTP 连接并设置请求头</li>
     *   <li>写入请求体数据</li>
     *   <li>读取响应内容</li>
     * </ul>
     *
     * @param requestBody 请求体 JSON 字符串
     * @param apiKey API 密钥
     * @return API 响应的字符串内容
     * @throws IOException 网络 IO 异常
     */
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

        Slog.d(TAG, "Writing request body, length: " + requestBody.length());

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        Slog.d(TAG, "Received response code: " + responseCode);

        if (responseCode != 200) {
            String errorBody = readStream(conn.getErrorStream());
            Slog.e(TAG, "HTTP error: " + responseCode + ", body: " + errorBody);
            throw new IOException("HTTP " + responseCode + ": " + errorBody);
        }

        return readStream(conn.getInputStream());
    }

    /**
     * 读取输入流内容
     *
     * <p>将 InputStream 中的所有数据读取到字符串中。
     *
     * @param is 输入流，可能为 null
     * @return 读取的字符串内容，如果输入流为 null 则返回空字符串
     * @throws IOException IO 异常
     */
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

    /**
     * 解析流式响应
     *
     * <p>MiniMax API 使用 SSE（Server-Sent Events）格式返回流式响应。
     * 每个事件以 "data: " 开头，响应体包含多种类型的事件：
     * <ul>
     *   <li>content_block_start - 内容块开始</li>
     *   <li>content_block_delta - 内容块增量（thinking/text/tool_use）</li>
     *   <li>message_delta - 消息结束信息</li>
     *   <li>[DONE] - 流式响应结束标记</li>
     * </ul>
     *
     * @param response API 返回的原始响应字符串
     * @param callback 回调接口，用于处理不同类型的响应数据
     * @throws JSONException JSON 解析异常
     */
    private void parseResponse(String response, Callback callback) throws JSONException {
        Slog.d(TAG, "Parsing response, total length: " + response.length());
        String[] lines = response.split("\n");

        for (String line : lines) {
            if (line.startsWith("data: ")) {
                String data = line.substring(6);
                if (data.equals("[DONE]")) {
                    Slog.d(TAG, "Received [DONE] marker, completing");
                    callback.onComplete();
                    return;
                }

                JSONObject json = new JSONObject(data);
                String type = json.optString("type");

                if ("content_block_start".equals(type)) {
                    // 内容块开始
                    JSONObject contentBlock = json.optJSONObject("content_block");
                    if (contentBlock != null && "text".equals(contentBlock.optString("type"))) {
                        Slog.d(TAG, "Content block start: text");
                    }
                } else if ("content_block_delta".equals(type)) {
                    // 内容块增量更新
                    JSONObject delta = json.optJSONObject("delta");
                    if (delta != null) {
                        String deltaType = delta.optString("type");
                        if ("thinking_delta".equals(deltaType)) {
                            // AI 思考过程
                            String thinking = delta.optString("thinking", "");
                            callback.onThinking(thinking);
                        } else if ("text_delta".equals(deltaType)) {
                            // AI 文本回复
                            String text = delta.optString("text", "");
                            callback.onText(text);
                        } else if ("tool_use_delta".equals(deltaType)) {
                            // AI 请求调用工具
                            String toolName = delta.optString("name", "");
                            String toolInput = delta.optString("input", "");
                            Slog.d(TAG, "Tool call: " + toolName + ", input: " + toolInput);
                            try {
                                Bundle args = jsonToBundle(new JSONObject(toolInput));
                                callback.onToolCall(toolName, args);
                            } catch (JSONException e) {
                                Slog.w(TAG, "Failed to parse tool input", e);
                            }
                        }
                    }
                } else if ("message_delta".equals(type)) {
                    // 消息结束，包含停止原因
                    JSONObject delta = json.optJSONObject("delta");
                    if (delta != null && delta.has("stop_reason")) {
                        String stopReason = delta.optString("stop_reason");
                        Slog.d(TAG, "Message delta, stop_reason: " + stopReason);
                    }
                }
            }
        }

        Slog.d(TAG, "Response parsing complete");
        callback.onComplete();
    }

    /**
     * 将 JSONObject 转换为 Android Bundle
     *
     * <p>递归地将 JSON 对象及其嵌套结构转换为 Android Bundle。
     * 支持的基本类型包括：String、Integer、Long、Double、Boolean。
     * JSONObject 会被递归转换为嵌套 Bundle，JSONArray 会被转换为字符串。
     *
     * @param json 要转换的 JSON 对象
     * @return 转换后的 Bundle
     * @throws JSONException JSON 解析异常
     */
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

    /**
     * 将 Bitmap 转换为 PNG 字节数组
     *
     * <p>这是一个工具方法，用于将 Android Bitmap 对象转换为 PNG 格式的字节数组。
     * 返回的字节数组可以直接用于 Base64 编码后发送 API 请求。
     *
     * @param bitmap 要转换的 Bitmap 对象
     * @return PNG 格式的字节数组，如果输入为 null 则返回 null
     */
    public static byte[] bitmapToPng(Bitmap bitmap) {
        if (bitmap == null) return null;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        Slog.d(TAG, "Bitmap converted to PNG, size: " + stream.size() + " bytes");
        return stream.toByteArray();
    }
}
