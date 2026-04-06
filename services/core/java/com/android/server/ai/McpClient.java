package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;

import com.android.server.UiThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * McpClient - MCP (Model Context Protocol) TCP Socket 客户端
 *
 * <p>负责与 Python MCP Server 通过 TCP Socket 进行 JSON-RPC 2.0 通信：
 * <ul>
 *   <li>建立/断开 TCP 连接</li>
 *   <li>发送 JSON-RPC 2.0 请求并接收响应</li>
 *   <li>处理 JSON-RPC 通知（notifications）</li>
 *   <li>工具列表查询和工具调用</li>
 * </ul>
 *
 * <p>JSON-RPC 2.0 协议规范：
 * <ul>
 *   <li>请求: {"jsonrpc": "2.0", "id": number, "method": string, "params": object}</li>
 *   <li>响应: {"jsonrpc": "2.0", "id": number, "result": object}</li>
 *   <li>错误: {"jsonrpc": "2.0", "id": number, "error": {"code": number, "message": string}}</li>
 * </ul>
 */
public class McpClient {
    private static final String TAG = "McpClient";
    private static final String TAG_RPC = "McpClient-RPC";

    // JSON-RPC 方法名
    private static final String METHOD_LIST_TOOLS = "tools/list";
    private static final String METHOD_CALL_TOOL = "tools/call";

    // 超时配置
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int TOOL_CALL_TIMEOUT_MS = 60000;

    // 重试配置
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    // 连接状态
    private boolean mConnected = false;
    private String mHost;
    private int mPort;

    // Socket 和流
    private Socket mSocket;
    private BufferedReader mReader;
    private OutputStreamWriter mWriter;

    // 线程安全
    private final Object mWriteLock = new Object();
    private final Map<Integer, JsonRpcRequest> mPendingRequests = new ConcurrentHashMap<>();
    private final AtomicInteger mNextRequestId = new AtomicInteger(1);

    // 回调
    private McpCallback mCallback;
    private Handler mHandler;

    // JSON 读取缓冲
    private StringBuilder mReadBuffer = new StringBuilder();

    /**
     * McpCallback - MCP 客户端回调接口
     */
    public interface McpCallback {
        /** 连接成功 */
        default void onConnected() {}
        /** 连接断开 */
        default void onDisconnected() {}
        /** 收到工具列表 */
        default void onToolsList(@NonNull List<McpTool> tools) {}
        /** 工具调用结果 */
        default void onToolResult(@NonNull String toolName, @NonNull Bundle result) {}
        /** 收到错误 */
        default void onError(@NonNull String error) {}
        /** 连接失败 */
        default void onConnectFailed(@NonNull String error) {}
    }

    /**
     * McpCallbackWrapper - 封装回调，支持 CountDownLatch 同步等待
     */
    private static class McpCallbackWrapper implements McpCallback {
        private McpCallback mOriginal;
        private final CountDownLatch mLatch;

        McpCallbackWrapper(McpCallback original, CountDownLatch latch) {
            this.mOriginal = original;
            this.mLatch = latch;
        }

        @Override
        public void onConnected() {
            if (mOriginal != null) mOriginal.onConnected();
        }

        @Override
        public void onDisconnected() {
            if (mOriginal != null) mOriginal.onDisconnected();
        }

        @Override
        public void onToolsList(List<McpTool> tools) {
            if (mOriginal != null) mOriginal.onToolsList(tools);
        }

        @Override
        public void onToolResult(String toolName, Bundle result) {
            if (mOriginal != null) mOriginal.onToolResult(toolName, result);
        }

        @Override
        public void onError(String error) {
            if (mOriginal != null) mOriginal.onError(error);
        }

        @Override
        public void onConnectFailed(String error) {
            if (mOriginal != null) mOriginal.onConnectFailed(error);
        }
    }

    /**
     * JsonRpcRequest - JSON-RPC 请求上下文
     */
    private static class JsonRpcRequest {
        final int id;
        final String method;
        final JSONObject params;
        final CountDownLatch latch;
        JSONObject result;
        String error;
        boolean completed = false;

        JsonRpcRequest(int id, String method, JSONObject params, CountDownLatch latch) {
            this.id = id;
            this.method = method;
            this.params = params;
            this.latch = latch;
        }
    }

    /** 构造 McpClient 实例 */
    public McpClient() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 连接到 MCP Server
     * @param host 主机地址（通常为 127.0.0.1）
     * @param port 端口号
     * @param callback 连接状态回调
     * @return 是否连接成功
     */
    public boolean connect(@NonNull String host, int port, @NonNull McpCallback callback) {
        mHost = host;
        mPort = port;
        mCallback = callback;

        return retryOperation(() -> {
            try {
                doConnect();
                return true;
            } catch (IOException e) {
                Slog.e(TAG, "Failed to connect to MCP server at " + host + ":" + port, e);
                postConnectFailed("Connection failed: " + e.getMessage());
                return false;
            }
        }, MAX_RETRY_ATTEMPTS);
    }

    private void doConnect() throws IOException {
        Slog.i(TAG, "Connecting to MCP server at " + mHost + ":" + mPort);

        mSocket = new Socket();
        mSocket.connect(new InetSocketAddress(mHost, mPort), CONNECT_TIMEOUT_MS);
        mSocket.setSoTimeout(READ_TIMEOUT_MS);

        mReader = new BufferedReader(new InputStreamReader(mSocket.getInputStream(), StandardCharsets.UTF_8));
        mWriter = new OutputStreamWriter(mSocket.getOutputStream(), StandardCharsets.UTF_8);

        mConnected = true;
        Slog.i(TAG, "Connected to MCP server");

        postConnected();

        // 启动读取线程
        startReadingThread();
    }

    /**
     * 断开与 MCP Server 的连接
     */
    public void disconnect() {
        Slog.i(TAG, "Disconnecting from MCP server");

        mConnected = false;

        try {
            if (mSocket != null) {
                mSocket.close();
            }
            if (mReader != null) {
                mReader.close();
            }
            if (mWriter != null) {
                mWriter.close();
            }
        } catch (IOException e) {
            Slog.w(TAG, "Error closing connection", e);
        } finally {
            mSocket = null;
            mReader = null;
            mWriter = null;
        }

        // 清理待处理的请求
        for (JsonRpcRequest request : mPendingRequests.values()) {
            request.error = "Connection closed";
            request.completed = true;
            request.latch.countDown();
        }
        mPendingRequests.clear();

        postDisconnected();
        Slog.i(TAG, "Disconnected from MCP server");
    }

    /**
     * 检查是否已连接
     * @return 是否已连接
     */
    public boolean isConnected() {
        return mConnected && mSocket != null && mSocket.isConnected();
    }

    /**
     * 列出所有可用工具
     * @param callback 回调（支持同步等待）
     * @return 工具列表（同步模式下有效）
     */
    public List<McpTool> listTools(@Nullable McpCallback callback) {
        CountDownLatch latch = new CountDownLatch(1);
        McpCallbackWrapper wrapper = new McpCallbackWrapper(callback, latch);
        final List<McpTool>[] result = new List[1];
        final String[] error = new String[1];

        wrapper.mOriginal = new McpCallback() {
            @Override
            public void onToolsList(List<McpTool> tools) {
                result[0] = tools;
            }

            @Override
            public void onError(String err) {
                error[0] = err;
            }
        };

        sendRequest(METHOD_LIST_TOOLS, new JSONObject(), wrapper);

        // 等待响应
        try {
            if (!latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Slog.w(TAG, "listTools timed out");
                error[0] = "Timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error[0] = "Interrupted";
        }

        if (error[0] != null) {
            Slog.e(TAG, "listTools failed: " + error[0]);
        }

        return result[0] != null ? result[0] : new ArrayList<>();
    }

    /**
     * 异步列出所有可用工具
     * @param callback 回调
     */
    public void listToolsAsync(@Nullable McpCallback callback) {
        sendRequest(METHOD_LIST_TOOLS, new JSONObject(), callback);
    }

    /**
     * 调用 MCP 工具（同步等待）
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @param callback 回调
     * @return 工具执行结果 Bundle
     */
    public Bundle callTool(@NonNull String toolName, @NonNull Bundle arguments,
                           @Nullable McpCallback callback) {
        CountDownLatch latch = new CountDownLatch(1);
        final Bundle[] result = new Bundle[1];
        final String[] error = new String[1];

        McpCallback wrapper = new McpCallback() {
            @Override
            public void onToolResult(String name, Bundle res) {
                if (name.equals(toolName)) {
                    result[0] = res;
                }
            }

            @Override
            public void onError(String err) {
                error[0] = err;
            }
        };

        try {
            JSONObject params = new JSONObject();
            params.put("name", toolName);
            params.put("arguments", bundleToJson(arguments));

            sendRequest(METHOD_CALL_TOOL, params, wrapper);

            if (!latch.await(TOOL_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Slog.w(TAG, "callTool timed out for " + toolName);
                error[0] = "Timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error[0] = "Interrupted";
        } catch (JSONException e) {
            error[0] = "JSON error: " + e.getMessage();
        }

        if (error[0] != null) {
            Slog.e(TAG, "callTool failed for " + toolName + ": " + error[0]);
            Bundle errBundle = new Bundle();
            errBundle.putString("status", "error");
            errBundle.putString("message", error[0]);
            return errBundle;
        }

        return result[0] != null ? result[0] : createEmptyResult();
    }

    /**
     * 发送 JSON-RPC 请求
     */
    private void sendRequest(String method, JSONObject params, @Nullable McpCallback callback) {
        if (!isConnected()) {
            Slog.w(TAG, "Not connected, cannot send request: " + method);
            if (callback != null) {
                postError("Not connected");
            }
            return;
        }

        int id = mNextRequestId.getAndIncrement();
        CountDownLatch latch = new CountDownLatch(1);

        JsonRpcRequest request = new JsonRpcRequest(id, method, params, latch);
        mPendingRequests.put(id, request);

        JSONObject jsonRpc = new JSONObject();
        try {
            jsonRpc.put("jsonrpc", "2.0");
            jsonRpc.put("id", id);
            jsonRpc.put("method", method);
            jsonRpc.put("params", params);
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to build JSON-RPC request", e);
            mPendingRequests.remove(id);
            return;
        }

        String requestStr = jsonRpc.toString();
        Slog.d(TAG_RPC, "Sending request: " + requestStr);

        synchronized (mWriteLock) {
            try {
                mWriter.write(requestStr + "\n");
                mWriter.flush();
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write request", e);
                mPendingRequests.remove(id);
                postError("Write failed: " + e.getMessage());
                return;
            }
        }

        // 如果有回调，等待完成并处理响应
        if (callback != null) {
            new Thread(() -> {
                try {
                    if (!latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        Slog.w(TAG, "Request timed out: " + method);
                        mHandler.post(() -> callback.onError("Timeout"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    /**
     * 启动 JSON-RPC 响应读取线程
     */
    private void startReadingThread() {
        Thread readerThread = new Thread(() -> {
            Slog.i(TAG, "JSON-RPC reader thread started");
            try {
                String line;
                while (mConnected && (line = mReader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    handleMessage(line);
                }
            } catch (IOException e) {
                if (mConnected) {
                    Slog.e(TAG, "Reader thread error", e);
                    mHandler.post(this::handleDisconnect);
                }
            }
            Slog.i(TAG, "JSON-RPC reader thread ended");
        }, "McpClient-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * 处理收到的 JSON-RPC 消息
     */
    private void handleMessage(String message) {
        Slog.d(TAG_RPC, "Received: " + message);

        try {
            JSONObject json = new JSONObject(message);

            // 检查是否是响应消息
            if (json.has("id")) {
                int id = json.getInt("id");
                JsonRpcRequest request = mPendingRequests.remove(id);

                if (request != null) {
                    if (json.has("error")) {
                        JSONObject error = json.getJSONObject("error");
                        request.error = error.optString("message", "Unknown error");
                        Slog.w(TAG, "JSON-RPC error for request " + id + ": " + request.error);
                        mHandler.post(() -> mCallback.onError(request.error));
                    } else if (json.has("result")) {
                        JSONObject result = json.getJSONObject("result");
                        handleResult(request.method, result);
                    }
                    request.completed = true;
                    request.latch.countDown();
                }
            } else if (json.has("method")) {
                // 通知消息（server -> client）
                String method = json.getString("method");
                JSONObject params = json.optJSONObject("params");
                handleNotification(method, params);
            }
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to parse JSON-RPC message", e);
        }
    }

    /**
     * 处理 JSON-RPC 结果
     */
    private void handleResult(String method, JSONObject result) {
        mHandler.post(() -> {
            try {
                switch (method) {
                    case METHOD_LIST_TOOLS:
                        handleToolsListResult(result);
                        break;
                    case METHOD_CALL_TOOL:
                        handleCallToolResult(result);
                        break;
                    default:
                        Slog.w(TAG, "Unknown method result: " + method);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error handling result for " + method, e);
            }
        });
    }

    /**
     * 处理工具列表结果
     */
    private void handleToolsListResult(JSONObject result) throws JSONException {
        List<McpTool> tools = new ArrayList<>();
        JSONArray toolArray = result.optJSONArray("tools");

        if (toolArray != null) {
            for (int i = 0; i < toolArray.length(); i++) {
                JSONObject toolJson = toolArray.getJSONObject(i);
                String name = toolJson.optString("name", "");
                String description = toolJson.optString("description", "");
                JSONObject inputSchema = toolJson.optJSONObject("inputSchema");

                if (!name.isEmpty()) {
                    tools.add(new McpTool(name, description,
                            inputSchema != null ? inputSchema : new JSONObject()));
                }
            }
        }

        Slog.i(TAG, "Received " + tools.size() + " tools from MCP server");
        if (mCallback != null) {
            mCallback.onToolsList(tools);
        }
    }

    /**
     * 处理工具调用结果
     */
    private void handleCallToolResult(JSONObject result) throws JSONException {
        String toolName = result.optString("name", "unknown");
        Bundle bundle = new Bundle();

        JSONArray content = result.optJSONArray("content");
        if (content != null && content.length() > 0) {
            JSONObject first = content.getJSONObject(0);
            String text = first.optString("text", "");
            bundle.putString("content", text);

            // 尝试解析为 JSON
            try {
                JSONObject contentJson = new JSONObject(text);
                java.util.Iterator<String> keys = contentJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    bundle.putString(key, contentJson.getString(key));
                }
            } catch (JSONException e) {
                // 不是 JSON，直接使用 text
            }
        }

        boolean isError = result.optBoolean("isError", false);
        bundle.putString("status", isError ? "error" : "success");

        Slog.i(TAG, "Tool " + toolName + " returned: " + bundle.getString("status"));
        if (mCallback != null) {
            mCallback.onToolResult(toolName, bundle);
        }
    }

    /**
     * 处理 JSON-RPC 通知
     */
    private void handleNotification(String method, JSONObject params) {
        Slog.d(TAG, "Received notification: " + method);
        // 目前 MCP 暂不支持通知消息，后续可扩展
    }

    private void handleDisconnect() {
        mConnected = false;
        postDisconnected();
    }

    private void postConnected() {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onConnected());
        }
    }

    private void postDisconnected() {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onDisconnected());
        }
    }

    private void postError(String error) {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onError(error));
        }
    }

    private void postConnectFailed(String error) {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onConnectFailed(error));
        }
    }

    /**
     * 将 Bundle 转换为 JSONObject
     */
    private JSONObject bundleToJson(Bundle bundle) throws JSONException {
        JSONObject json = new JSONObject();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value instanceof String) {
                json.put(key, value);
            } else if (value instanceof Integer) {
                json.put(key, value);
            } else if (value instanceof Boolean) {
                json.put(key, value);
            } else if (value instanceof Double) {
                json.put(key, value);
            } else if (value instanceof Long) {
                json.put(key, value);
            } else {
                json.put(key, String.valueOf(value));
            }
        }
        return json;
    }

    private Bundle createEmptyResult() {
        Bundle bundle = new Bundle();
        bundle.putString("status", "error");
        bundle.putString("message", "No result received");
        return bundle;
    }

    /**
     * 重试操作
     */
    private boolean retryOperation(java.util.concurrent.Callable<Boolean> operation,
                                   int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                if (operation.call()) {
                    return true;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Attempt " + (i + 1) + " failed", e);
            }
            if (i < maxAttempts - 1) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * (i + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
