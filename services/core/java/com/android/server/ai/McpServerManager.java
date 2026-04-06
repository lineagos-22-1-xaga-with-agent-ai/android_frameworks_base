package com.android.server.ai;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * McpServerManager - Python MCP Server 生命周期管理器
 *
 * <p>负责：
 * <ul>
 *   <li>启动/停止 Python MCP Server 进程</li>
 *   <li>自动重启崩溃的 Server（最多 3 次）</li>
 *   <li>端口分配和管理</li>
 *   <li>多 Server 支持</li>
 * </ul>
 *
 * <p>使用 ProcessBuilder 启动 Python 进程，通过 TCP Socket 与之通信。
 *
 * @see McpClient
 */
public class McpServerManager {
    private static final String TAG = "McpServerManager";

    // Python 环境配置
    private static final String PYTHON_BIN = "/system/bin/python3";
    private static final String DEFAULT_SERVER_SCRIPT = "/data/local/tmp/mcp_server.py";

    // 重启配置
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static final long RESTART_DELAY_MS = 5000;
    private static final long PORT_CHECK_TIMEOUT_MS = 10000;

    // 默认端口范围
    private static final int DEFAULT_PORT_RANGE_START = 9080;
    private static final int DEFAULT_PORT_RANGE_END = 9090;

    // Server 配置
    private static final int DEFAULT_PORT = 9080;

    // 上下文
    private final Context mContext;
    private final Handler mHandler;

    // Server 进程
    private Process mServerProcess;
    private int mServerPort;
    private boolean mShouldRun = false;
    private int mRestartAttempts = 0;

    // MCP Client
    private McpClient mMcpClient;
    private McpCallback mCallback;

    // 多 Server 支持
    private final Map<String, ServerConfig> mServers = new ConcurrentHashMap<>();
    private final AtomicInteger mNextPort = new AtomicInteger(DEFAULT_PORT_RANGE_START);

    // Server 输出读取线程
    private Thread mOutputReaderThread;

    /**
     * ServerConfig - MCP Server 配置
     */
    public static class ServerConfig {
        public final String id;
        public final String scriptPath;
        public int port;
        public Process process;
        public McpClient client;
        public boolean enabled;
        public int restartAttempts;

        public ServerConfig(String id, String scriptPath, int port) {
            this.id = id;
            this.scriptPath = scriptPath;
            this.port = port;
            this.enabled = true;
            this.restartAttempts = 0;
        }
    }

    /**
     * McpCallback - 事件回调接口
     */
    public interface McpCallback {
        default void onServerStarted(int port) {}
        default void onServerStopped() {}
        default void onServerCrashed(String reason) {}
        default void onToolsReceived(ServerConfig config) {}
        default void onError(String error) {}
    }

    /** 构造 McpServerManager 实例 */
    public McpServerManager(@NonNull Context context) {
        this.mContext = context;
        this.mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置回调
     * @param callback 回调接口
     */
    public void setCallback(@Nullable McpCallback callback) {
        this.mCallback = callback;
    }

    /**
     * 启动默认 MCP Server
     * @return 是否启动成功
     */
    public boolean startDefaultServer() {
        return startServer("default", DEFAULT_SERVER_SCRIPT, DEFAULT_PORT);
    }

    /**
     * 启动 MCP Server
     * @param id Server ID
     * @param scriptPath Python 脚本路径
     * @param port 端口号（0 表示自动分配）
     * @return 是否启动成功
     */
    public boolean startServer(@NonNull String id, @NonNull String scriptPath, int port) {
        Slog.i(TAG, "Starting MCP server: id=" + id + ", script=" + scriptPath + ", port=" + port);

        // 检查脚本是否存在
        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            Slog.e(TAG, "MCP server script not found: " + scriptPath);
            postError("Script not found: " + scriptPath);
            return false;
        }

        // 自动分配端口
        if (port <= 0) {
            port = assignPort();
        }

        // 检查是否已存在
        ServerConfig existing = mServers.get(id);
        if (existing != null && existing.enabled) {
            Slog.w(TAG, "Server already running: " + id);
            return true;
        }

        // 创建 Server 配置
        ServerConfig config = new ServerConfig(id, scriptPath, port);
        mServers.put(id, config);

        // 标记为应该运行
        mShouldRun = true;
        mRestartAttempts = 0;

        // 启动 Server
        return doStartServer(config);
    }

    /**
     * 执行实际的 Server 启动
     */
    private boolean doStartServer(@NonNull ServerConfig config) {
        Slog.i(TAG, "doStartServer: " + config.id + " on port " + config.port);

        // 先检查端口是否可用
        if (isPortInUse("127.0.0.1", config.port)) {
            Slog.w(TAG, "Port " + config.port + " already in use, trying next port");
            config.port = assignPort();
            if (config.port <= 0) {
                postError("No available port in range");
                return false;
            }
        }

        // 构建命令
        ProcessBuilder pb = new ProcessBuilder(
                PYTHON_BIN,
                config.scriptPath,
                "--tcp",
                String.valueOf(config.port)
        );
        pb.redirectErrorStream(true);

        try {
            // 启动进程
            config.process = pb.start();
            mServerProcess = config.process;

            // 启动输出读取线程
            startOutputReader(config);

            // 等待 Server 启动（检查端口）
            if (waitForPort(config.port, PORT_CHECK_TIMEOUT_MS)) {
                Slog.i(TAG, "MCP server started successfully on port " + config.port);
                postServerStarted(config.port);
                config.restartAttempts = 0;

                // 连接 MCP Client
                connectToServer(config);

                return true;
            } else {
                Slog.w(TAG, "MCP server port check timeout");
                stopServerProcess(config);
                handleServerCrash(config, "Port check timeout");
                return false;
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to start MCP server", e);
            postError("Failed to start: " + e.getMessage());
            handleServerCrash(config, e.getMessage());
            return false;
        }
    }

    /**
     * 连接到已启动的 MCP Server
     */
    private void connectToServer(@NonNull ServerConfig config) {
        mMcpClient = new McpClient();

        McpClient.McpCallback clientCallback = new McpClient.McpCallback() {
            @Override
            public void onConnected() {
                Slog.i(TAG, "MCP client connected to server: " + config.id);
                // 请求工具列表
                mMcpClient.listToolsAsync(this);
            }

            @Override
            public void onToolsList(java.util.List<McpTool> tools) {
                Slog.i(TAG, "Received " + tools.size() + " tools from server: " + config.id);
                if (mCallback != null) {
                    mCallback.onToolsReceived(config);
                }
            }

            @Override
            public void onDisconnected() {
                Slog.w(TAG, "MCP client disconnected from server: " + config.id);
            }

            @Override
            public void onError(String error) {
                Slog.e(TAG, "MCP client error: " + error);
            }

            @Override
            public void onConnectFailed(String error) {
                Slog.e(TAG, "MCP client connection failed: " + error);
            }
        };

        boolean connected = mMcpClient.connect("127.0.0.1", config.port, clientCallback);
        if (connected) {
            config.client = mMcpClient;
        }
    }

    /**
     * 停止 MCP Server
     * @param id Server ID
     */
    public void stopServer(@NonNull String id) {
        Slog.i(TAG, "Stopping MCP server: " + id);

        ServerConfig config = mServers.get(id);
        if (config == null) {
            Slog.w(TAG, "Server not found: " + id);
            return;
        }

        mShouldRun = false;
        stopServerProcess(config);
        mServers.remove(id);

        postServerStopped();
    }

    /**
     * 停止所有 MCP Server
     */
    public void stopAllServers() {
        Slog.i(TAG, "Stopping all MCP servers");
        mShouldRun = false;

        for (ServerConfig config : mServers.values()) {
            stopServerProcess(config);
        }
        mServers.clear();
    }

    /**
     * 停止 Server 进程
     */
    private void stopServerProcess(@NonNull ServerConfig config) {
        if (config.client != null) {
            config.client.disconnect();
            config.client = null;
        }

        if (config.process != null) {
            config.process.destroy();
            try {
                config.process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            config.process = null;
        }

        if (mOutputReaderThread != null) {
            mOutputReaderThread.interrupt();
            mOutputReaderThread = null;
        }
    }

    /**
     * 处理 Server 崩溃
     */
    private void handleServerCrash(@NonNull ServerConfig config, @NonNull String reason) {
        Slog.w(TAG, "MCP server crashed: " + reason);

        postServerCrashed(reason);

        // 尝试重启
        if (mShouldRun && config.restartAttempts < MAX_RESTART_ATTEMPTS) {
            config.restartAttempts++;
            Slog.i(TAG, "Restarting MCP server, attempt " + config.restartAttempts);

            mHandler.postDelayed(() -> {
                if (mShouldRun) {
                    doStartServer(config);
                }
            }, RESTART_DELAY_MS * config.restartAttempts);
        } else if (config.restartAttempts >= MAX_RESTART_ATTEMPTS) {
            Slog.e(TAG, "Max restart attempts reached, giving up");
            postError("Max restart attempts reached");
            mShouldRun = false;
        }
    }

    /**
     * 启动 Server 输出读取线程
     */
    private void startOutputReader(@NonNull ServerConfig config) {
        mOutputReaderThread = new Thread(() -> {
            try {
                InputStream is = config.process.getInputStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8));

                String line;
                while ((line = reader.readLine()) != null) {
                    Slog.d(TAG, "MCP server output: " + line);
                }
            } catch (IOException e) {
                if (mShouldRun) {
                    Slog.e(TAG, "Error reading server output", e);
                }
            }
        }, "McpServer-OutputReader");
        mOutputReaderThread.setDaemon(true);
        mOutputReaderThread.start();
    }

    /**
     * 检查端口是否被占用
     */
    private boolean isPortInUse(@NonNull String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 等待端口可用
     */
    private boolean waitForPort(int port, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isPortInUse("127.0.0.1", port)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 分配可用端口
     */
    private int assignPort() {
        for (int i = 0; i < 10; i++) {
            int port = mNextPort.getAndIncrement() % DEFAULT_PORT_RANGE_END;
            if (port < DEFAULT_PORT_RANGE_START) {
                port = DEFAULT_PORT_RANGE_START;
            }
            if (!isPortInUse("127.0.0.1", port)) {
                return port;
            }
        }
        return 0;
    }

    /**
     * 检查 Server 是否运行中
     * @param id Server ID
     * @return 是否运行中
     */
    public boolean isServerRunning(@NonNull String id) {
        ServerConfig config = mServers.get(id);
        return config != null && config.enabled &&
               config.process != null && config.client != null;
    }

    /**
     * 获取 Server 的 MCP Client
     * @param id Server ID
     * @return McpClient 实例
     */
    public @Nullable McpClient getMcpClient(@NonNull String id) {
        ServerConfig config = mServers.get(id);
        return config != null ? config.client : null;
    }

    /**
     * 获取默认 MCP Client
     * @return McpClient 实例
     */
    public @Nullable McpClient getDefaultMcpClient() {
        return mMcpClient;
    }

    // Callback 辅助方法

    private void postServerStarted(int port) {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onServerStarted(port));
        }
    }

    private void postServerStopped() {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onServerStopped());
        }
    }

    private void postServerCrashed(String reason) {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onServerCrashed(reason));
        }
    }

    private void postError(String error) {
        if (mCallback != null) {
            mHandler.post(() -> mCallback.onError(error));
        }
    }
}
