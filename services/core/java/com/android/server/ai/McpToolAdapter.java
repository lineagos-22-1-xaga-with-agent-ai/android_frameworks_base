package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.util.Slog;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * McpToolAdapter - 将 MCP 工具适配为 MiniMaxClient.AgentTool 接口
 *
 * <p>作为 Java AgentTool 和 Python MCP Server 之间的桥梁：
 * <ul>
 *   <li>实现 AgentTool 接口，供 MiniMaxClient 调用</li>
 *   <li>通过 McpClient 与 Python MCP Server 通信</li>
 *   <li>处理同步/异步转换（CountDownLatch）</li>
 * </ul>
 *
 * @see MiniMaxClient.AgentTool
 * @see McpClient
 * @see McpTool
 */
public class McpToolAdapter implements MiniMaxClient.AgentTool {
    private static final String TAG = "McpToolAdapter";

    // 工具调用超时时间
    private static final long TOOL_CALL_TIMEOUT_MS = 60000;

    /** 原始 MCP 工具描述 */
    private final McpTool mTool;

    /** MCP Client 引用 */
    private final McpClient mClient;

    /**
     * 创建 MCP 工具适配器
     * @param tool MCP 工具描述
     * @param client MCP Client 实例
     */
    public McpToolAdapter(@NonNull McpTool tool, @NonNull McpClient client) {
        this.mTool = tool;
        this.mClient = client;
    }

    /**
     * 获取工具名称
     * @return 工具唯一名称
     */
    @Override
    public @NonNull String getName() {
        return mTool.name;
    }

    /**
     * 获取工具描述
     * @return 工具功能描述
     */
    @Override
    public @NonNull String getDescription() {
        return mTool.description;
    }

    /**
     * 获取输入参数 JSON Schema
     * @return JSON Schema 定义
     */
    @Override
    public @NonNull JSONObject getInputSchema() {
        return mTool.inputSchema;
    }

    /**
     * 执行 MCP 工具
     *
     * <p>通过 McpClient 发送工具调用请求到 Python MCP Server，
     * 同步等待结果返回。
     *
     * @param arguments 包含工具输入参数的 Bundle
     * @return 执行结果 Bundle
     * @throws Exception 执行过程中可能发生的异常
     */
    @Override
    public @NonNull Bundle execute(@Nullable Bundle arguments) throws Exception {
        if (!mClient.isConnected()) {
            Slog.w(TAG, "MCP client not connected, cannot execute tool: " + mTool.name);
            Bundle errorResult = new Bundle();
            errorResult.putString("status", "error");
            errorResult.putString("message", "MCP client not connected");
            return errorResult;
        }

        Slog.i(TAG, "Executing MCP tool: " + mTool.name + " with args: " + arguments);

        // 确保 arguments 不为 null
        Bundle args = arguments != null ? arguments : new Bundle();

        // 使用 CountDownLatch 同步等待异步结果
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bundle> resultRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        // 创建回调
        McpClient.McpCallback callback = new McpClient.McpCallback() {
            @Override
            public void onToolResult(@NonNull String toolName, @NonNull Bundle result) {
                if (toolName.equals(mTool.name)) {
                    resultRef.set(result);
                    latch.countDown();
                }
            }

            @Override
            public void onError(@NonNull String error) {
                Slog.e(TAG, "MCP tool error: " + error);
                errorRef.set(error);
                latch.countDown();
            }

            @Override
            public void onDisconnected() {
                errorRef.set("MCP server disconnected");
                latch.countDown();
            }
        };

        // 发送工具调用请求
        try {
            Bundle result = mClient.callTool(mTool.name, args, callback);

            // 等待完成
            boolean completed = latch.await(TOOL_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                Slog.w(TAG, "Tool call timed out: " + mTool.name);
                Bundle timeoutResult = new Bundle();
                timeoutResult.putString("status", "error");
                timeoutResult.putString("message", "Tool call timeout");
                return timeoutResult;
            }

            // 检查错误
            String error = errorRef.get();
            if (error != null) {
                Bundle errorResult = new Bundle();
                errorResult.putString("status", "error");
                errorResult.putString("message", error);
                return errorResult;
            }

            Bundle result2 = resultRef.get();
            if (result2 != null) {
                Slog.i(TAG, "MCP tool executed successfully: " + mTool.name);
                return result2;
            }

            // Fallback: 返回之前的 result
            if (result != null) {
                return result;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Slog.e(TAG, "Tool call interrupted: " + mTool.name, e);
            Bundle interruptResult = new Bundle();
            interruptResult.putString("status", "error");
            interruptResult.putString("message", "Interrupted");
            return interruptResult;
        }

        // 默认返回空结果
        Bundle defaultResult = new Bundle();
        defaultResult.putString("status", "error");
        defaultResult.putString("message", "Unknown error");
        return defaultResult;
    }

    /**
     * 获取底层 MCP 工具描述
     * @return McpTool 实例
     */
    public @NonNull McpTool getMcpTool() {
        return mTool;
    }

    @Override
    public String toString() {
        return "McpToolAdapter{name='" + mTool.name + "', description='" + mTool.description + "'}";
    }
}
