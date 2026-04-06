package com.android.server.ai;

import android.util.Log;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLSocketFactory;

/**
 * 字节跳动流式语音识别客户端 (MiniMaxStreamingASRClient)
 *
 * <p>实现字节跳动 ASR 服务的 WebSocket 流式识别协议。
 *
 * <p>使用示例：
 * <pre>
 * MiniMaxStreamingASRClient client = new MiniMaxStreamingASRClient.Builder()
 *     .setAppKey("app-key")
 *     .setAccessKey("access-key")
 *     .setResourceId("volc.bigasr.sauc.duration")
 *     .build();
 *
 * client.setCallback(new Callback() {
 *     {@literal @}Override
 *     public void onConnecting() {
 *         Log.d(TAG, "正在连接...");
 *     }
 *
 *     {@literal @}Override
 *     public void onConnected() {
 *         Log.d(TAG, "已连接");
 *     }
 *
 *     {@literal @}Override
 *     public void onTranscript(String text, boolean isFinal) {
 *         Log.d(TAG, "识别结果: " + text + " (isFinal=" + isFinal + ")");
 *     }
 *
 *     {@literal @}Override
 *     public void onError(String error) {
 *         Log.e(TAG, "错误: " + error);
 *     }
 *
 *     {@literal @}Override
 *     public void onClosed() {
 *         Log.d(TAG, "连接已关闭");
 *     }
 * });
 *
 * // 开始识别
 * client.connect();
 * client.sendAudio(audioData);  // 分包发送
 * client.sendAudio(audioData);
 * client.finish();  // 发送结束信号
 * </pre>
 *
 * @author Android System
 */
public class MiniMaxStreamingASRClient implements Closeable {
    private static final String TAG = "MiniMaxStreamingASR";

    /** 日志开关 */
    private static final boolean DEBUG = true;

    // ==================== WebSocket 配置 ====================
    /** WebSocket 端点 */
    private static final String WS_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel";
    private static final String WS_PATH = "/api/v3/sauc/bigmodel";
    private static final String WS_HOST = "openspeech.bytedance.com";
    private static final int WS_PORT = 443;

    // ==================== 协议常量 ====================
    /** 协议版本 */
    private static final byte PROTOCOL_VERSION = 0x1;
    /** Header 大小 (4字节) */
    private static final byte HEADER_SIZE = 0x1; // header size = 4 bytes (1 * 4)

    /** 消息类型 */
    private static final byte MSG_TYPE_FULL_CLIENT_REQUEST = 0x1;
    private static final byte MSG_TYPE_AUDIO_ONLY_REQUEST = 0x2;
    private static final byte MSG_TYPE_FULL_SERVER_RESPONSE = 0x9;
    private static final byte MSG_TYPE_ERROR = 0xF;

    /** Message type specific flags */
    private static final byte FLAG_NONE = 0x0;
    private static final byte FLAG_POSITIVE_SEQUENCE = 0x1;
    private static final byte FLAG_LAST_PACKET = 0x2;
    private static final byte FLAG_NEGATIVE_SEQUENCE = 0x3;

    /** 序列化方法 */
    private static final byte SERIALIZATION_NONE = 0x0;
    private static final byte SERIALIZATION_JSON = 0x1;

    /** 压缩方式 */
    private static final byte COMPRESSION_NONE = 0x0;
    private static final byte COMPRESSION_GZIP = 0x1;

    // ==================== 音频配置 ====================
    /** 采样率 */
    private static final int SAMPLE_RATE = 16000;
    /** 每包音频时长 (ms) */
    private static final int PACKET_DURATION_MS = 200;
    /** 每包字节数 (16bit mono = 16000 * 200 / 1000 * 2 = 6400 bytes) */
    private static final int PACKET_SIZE = SAMPLE_RATE * PACKET_DURATION_MS / 1000 * 2;

    // ==================== 连接超时 ====================
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    // ==================== 成员变量 ====================
    private final String mAppKey;
    private final String mAccessKey;
    private final String mResourceId;
    private final String mConnectId;
    private final Map<String, String> mExtraHeaders;

    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mIsConnected = new AtomicBoolean(false);
    private final AtomicBoolean mIsConnecting = new AtomicBoolean(false);
    private final AtomicBoolean mIsSending = new AtomicBoolean(false);

    private Callback mCallback;
    private Socket mSocket;
    private DataInputStream mInputStream;
    private DataOutputStream mOutputStream;
    private ReaderThread mReaderThread;
    private int mSequence = 0;

    /**
     * ASR 回调接口
     */
    public interface Callback {
        /** 正在连接 */
        default void onConnecting() {}

        /** 已连接 */
        default void onConnected() {}

        /** 收到识别结果
         * @param text 识别文本
         * @param isFinal 是否是最终结果
         */
        void onTranscript(String text, boolean isFinal);

        /** 发生错误
         * @param error 错误信息
         */
        void onError(String error);

        /** 连接关闭 */
        default void onClosed() {}
    }

    /**
     * Builder 类
     */
    public static class Builder {
        private String mAppKey;
        private String mAccessKey;
        private String mResourceId = "volc.bigasr.sauc.duration";
        private Map<String, String> mExtraHeaders;

        public Builder setAppKey(String appKey) {
            mAppKey = appKey;
            return this;
        }

        public Builder setAccessKey(String accessKey) {
            mAccessKey = accessKey;
            return this;
        }

        public Builder setResourceId(String resourceId) {
            mResourceId = resourceId;
            return this;
        }

        public Builder setExtraHeaders(Map<String, String> headers) {
            mExtraHeaders = headers;
            return this;
        }

        public MiniMaxStreamingASRClient build() {
            if (mAppKey == null || mAppKey.isEmpty()) {
                throw new IllegalArgumentException("AppKey is required");
            }
            if (mAccessKey == null || mAccessKey.isEmpty()) {
                throw new IllegalArgumentException("AccessKey is required");
            }
            return new MiniMaxStreamingASRClient(this);
        }
    }

    private MiniMaxStreamingASRClient(Builder builder) {
        mAppKey = builder.mAppKey;
        mAccessKey = builder.mAccessKey;
        mResourceId = builder.mResourceId;
        mExtraHeaders = builder.mExtraHeaders;
        mConnectId = UUID.randomUUID().toString();

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "MiniMaxStreamingASRClient 初始化");
            Log.i(TAG, "  - ConnectId: " + mConnectId);
            Log.i(TAG, "  - ResourceId: " + mResourceId);
            Log.i(TAG, "  - PacketSize: " + PACKET_SIZE + " bytes");
            Log.i(TAG, "========================================");
        }
    }

    /**
     * 设置回调
     */
    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    /**
     * 连接服务器
     */
    public void connect() {
        if (mIsConnected.get() || mIsConnecting.get()) {
            Log.w(TAG, "已经在连接/连接状态");
            return;
        }

        mIsConnecting.set(true);
        mCallback.onConnecting();

        mExecutor.execute(this::doConnect);
    }

    private void doConnect() {
        try {
            if (DEBUG) Log.d(TAG, "正在建立 WebSocket 连接...");

            // 创建 Socket 并连接 (wss:// 需要 SSL)
            javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            mSocket = factory.createSocket(WS_HOST, WS_PORT);
            ((javax.net.ssl.SSLSocket) mSocket).startHandshake();

            // 进行 WebSocket 握手
            doHandshake();

            // 获取输入输出流
            mInputStream = new DataInputStream(mSocket.getInputStream());
            mOutputStream = new DataOutputStream(mSocket.getOutputStream());

            // 发送初始请求
            sendFullClientRequest();

            // 启动读取线程
            mIsConnected.set(true);
            mIsConnecting.set(false);
            mCallback.onConnected();

            if (DEBUG) Log.i(TAG, "WebSocket 连接已建立");

            mReaderThread = new ReaderThread();
            mReaderThread.start();

        } catch (Exception e) {
            mIsConnecting.set(false);
            mIsConnected.set(false);
            Log.e(TAG, "连接失败", e);
            mCallback.onError("连接失败: " + e.getMessage());
            closeQuietly();
        }
    }

    /**
     * WebSocket 握手
     */
    private void doHandshake() throws IOException {
        // 构建 HTTP GET 请求
        String handshake = "GET " + WS_PATH + " HTTP/1.1\r\n" +
                "Host: " + WS_HOST + ":" + WS_PORT + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + generateWebSocketKey() + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "X-Api-App-Key: " + mAppKey + "\r\n" +
                "X-Api-Access-Key: " + mAccessKey + "\r\n" +
                "X-Api-Resource-Id: " + mResourceId + "\r\n" +
                "X-Api-Connect-Id: " + mConnectId + "\r\n";

        // 添加额外 Header
        if (mExtraHeaders != null) {
            for (Map.Entry<String, String> entry : mExtraHeaders.entrySet()) {
                handshake += entry.getKey() + ": " + entry.getValue() + "\r\n";
            }
        }

        handshake += "\r\n";

        if (DEBUG) {
            Log.d(TAG, "发送 WebSocket 握手请求");
            Log.v(TAG, "Handshake:\n" + handshake);
        }

        // 发送握手请求
        OutputStream out = mSocket.getOutputStream();
        out.write(handshake.getBytes("UTF-8"));
        out.flush();

        // 读取握手响应
        byte[] buffer = new byte[1024];
        int len = mSocket.getInputStream().read(buffer);
        if (len <= 0) {
            throw new IOException("握手响应为空");
        }

        String response = new String(buffer, 0, len, "UTF-8");

        // 检查是否是错误响应 (400, 401, 403, 500 等)
        if (response.startsWith("HTTP/1.1 400") || response.startsWith("HTTP/1.1 401") ||
            response.startsWith("HTTP/1.1 403") || response.startsWith("HTTP/1.1 500")) {

            // 解析 Content-Length 获取错误 body
            String errorBody = "";
            String[] lines = response.split("\r\n");
            for (String line : lines) {
                if (line.startsWith("Content-Length:")) {
                    int contentLength = Integer.parseInt(line.substring(15).trim());
                    // 读取剩余的 body
                    byte[] bodyBytes = new byte[contentLength];
                    int bodyRead = mSocket.getInputStream().read(bodyBytes);
                    if (bodyRead > 0) {
                        errorBody = new String(bodyBytes, 0, bodyRead, "UTF-8");
                    }
                    break;
                }
            }

            String statusLine = lines[0];
            throw new IOException("服务器返回错误: " + statusLine + ", body: " + errorBody);
        }

        if (DEBUG) {
            Log.v(TAG, "握手响应:\n" + response);
        }

        // 检查握手是否成功
        if (!response.contains("101 Switching Protocols") &&
            !response.contains("HTTP/1.1 200")) {
            String statusLine = response.split("\r\n")[0];
            throw new IOException("WebSocket 握手失败: " + statusLine);
        }

        if (DEBUG) Log.d(TAG, "WebSocket 握手成功");
    }

    /**
     * 生成 WebSocket Key
     */
    private String generateWebSocketKey() {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            key.append((char) (Math.random() * 26 + 'a'));
        }
        return java.util.Base64.getEncoder().encodeToString(key.toString().getBytes());
    }

    /**
     * 发送初始请求 (Full Client Request)
     */
    private void sendFullClientRequest() throws IOException {
        // 构建请求 JSON
        String json = buildRequestJson();

        if (DEBUG) {
            Log.d(TAG, "发送 Full Client Request");
            Log.v(TAG, "Request JSON: " + json);
        }

        // 压缩 JSON
        byte[] jsonBytes = json.getBytes("UTF-8");
        byte[] compressed = gzipCompress(jsonBytes);

        // 构建 header
        // Byte 0: [4bits protocol version][4bits header size]
        // Byte 1: [4bits message type][4bits message type specific flags]
        // Byte 2: [4bits serialization][4bits compression]
        // Byte 3: Reserved
        ByteBuffer header = ByteBuffer.allocate(4);
        header.order(ByteOrder.BIG_ENDIAN);
        header.put((byte) ((PROTOCOL_VERSION << 4) | HEADER_SIZE));
        header.put((byte) ((MSG_TYPE_FULL_CLIENT_REQUEST << 4) | FLAG_NONE));
        header.put((byte) ((SERIALIZATION_JSON << 4) | COMPRESSION_GZIP));
        header.put((byte) 0x00);

        // 写入长度 (4 bytes, big endian)
        ByteBuffer lengthBuf = ByteBuffer.allocate(4);
        lengthBuf.order(ByteOrder.BIG_ENDIAN);
        lengthBuf.putInt(compressed.length);

        // 发送
        mOutputStream.write(header.array());
        mOutputStream.write(lengthBuf.array());
        mOutputStream.write(compressed);
        mOutputStream.flush();

        if (DEBUG) Log.d(TAG, "Full Client Request 已发送, payload长度=" + compressed.length);
    }

    /**
     * 构建请求 JSON
     */
    private String buildRequestJson() {
        return "{"
                + "\"user\":{\"uid\":\"android-device\"},"
                + "\"audio\":{"
                + "\"format\":\"raw\","
                + "\"rate\":16000,"
                + "\"bits\":16,"
                + "\"channel\":1"
                + "},"
                + "\"request\":{"
                + "\"model_name\":\"bigmodel\","
                + "\"enable_itn\":true,"
                + "\"enable_punc\":true,"
                + "\"enable_ddc\":false"
                + "}"
                + "}";
    }

    /**
     * 发送音频数据
     *
     * @param audioData 音频数据 (16bit PCM)
     * @param isLast 是否是最后一包
     */
    public void sendAudio(byte[] audioData, boolean isLast) {
        if (!mIsConnected.get()) {
            Log.w(TAG, "未连接，无法发送音频");
            return;
        }

        mExecutor.execute(() -> doSendAudio(audioData, isLast));
    }

    /**
     * 发送音频数据
     */
    private void doSendAudio(byte[] audioData, boolean isLast) {
        try {
            if (mIsSending.get()) {
                Log.w(TAG, "正在发送中，等待");
                return;
            }
            mIsSending.set(true);

            // 压缩音频
            byte[] compressed = gzipCompress(audioData);

            // 构建 header
            byte flags;
            if (isLast) {
                // 最后一包使用 FLAG_LAST_PACKET，不带 sequence
                flags = FLAG_LAST_PACKET;
            } else {
                // 普通包使用正数 sequence
                flags = FLAG_POSITIVE_SEQUENCE;
                mSequence++;
            }

            ByteBuffer header = ByteBuffer.allocate(4);
            header.order(ByteOrder.BIG_ENDIAN);
            header.put((byte) ((PROTOCOL_VERSION << 4) | HEADER_SIZE));
            header.put((byte) ((MSG_TYPE_AUDIO_ONLY_REQUEST << 4) | flags));
            header.put((byte) ((SERIALIZATION_NONE << 4) | COMPRESSION_GZIP));
            header.put((byte) 0x00);

            // 写入 sequence (仅非最后一包)
            if (!isLast) {
                ByteBuffer seqBuf = ByteBuffer.allocate(4);
                seqBuf.order(ByteOrder.BIG_ENDIAN);
                seqBuf.putInt(mSequence);
                mOutputStream.write(seqBuf.array());
            }

            // Sequence number (4 bytes, big endian)
            ByteBuffer seqBuf = ByteBuffer.allocate(4);
            seqBuf.order(ByteOrder.BIG_ENDIAN);
            seqBuf.putInt(isLast ? (int) (Integer.MIN_VALUE + (mSequence & 0x7FFFFFFF)) : mSequence);

            // Length (4 bytes, big endian)
            ByteBuffer lengthBuf = ByteBuffer.allocate(4);
            lengthBuf.order(ByteOrder.BIG_ENDIAN);
            lengthBuf.putInt(compressed.length);

            // 发送
            mOutputStream.write(header.array());
            mOutputStream.write(seqBuf.array());
            mOutputStream.write(lengthBuf.array());
            mOutputStream.write(compressed);
            mOutputStream.flush();

            if (DEBUG) {
                Log.v(TAG, "发送音频包: seq=" + mSequence + ", len=" + compressed.length + ", last=" + isLast);
            }

        } catch (Exception e) {
            Log.e(TAG, "发送音频失败", e);
            mCallback.onError("发送音频失败: " + e.getMessage());
        } finally {
            mIsSending.set(false);
        }
    }

    /**
     * 发送结束信号
     */
    public void finish() {
        if (!mIsConnected.get()) {
            return;
        }

        if (DEBUG) Log.d(TAG, "发送结束信号");

        // 发送一个空包作为结束信号
        try {
            byte[] emptyAudio = new byte[0];
            byte[] compressed = gzipCompress(emptyAudio);

            ByteBuffer header = ByteBuffer.allocate(4);
            header.order(ByteOrder.BIG_ENDIAN);
            header.put((byte) ((PROTOCOL_VERSION << 4) | HEADER_SIZE));
            header.put((byte) ((MSG_TYPE_AUDIO_ONLY_REQUEST << 4) | FLAG_NEGATIVE_SEQUENCE)); // 负包
            header.put((byte) ((SERIALIZATION_NONE << 4) | COMPRESSION_GZIP));
            header.put((byte) 0x00);

            ByteBuffer lengthBuf = ByteBuffer.allocate(4);
            lengthBuf.order(ByteOrder.BIG_ENDIAN);
            lengthBuf.putInt(compressed.length);

            mOutputStream.write(header.array());
            mOutputStream.write(lengthBuf.array());
            mOutputStream.flush();

        } catch (Exception e) {
            Log.e(TAG, "发送结束信号失败", e);
        }
    }

    /**
     * 读取线程
     */
    private class ReaderThread extends Thread {
        public ReaderThread() {
            super("ASR-ReaderThread");
        }

        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "ReaderThread 开始");

            try {
                while (mIsConnected.get() && !isInterrupted()) {
                    readMessage();
                }
            } catch (IOException e) {
                if (mIsConnected.get()) {
                    Log.e(TAG, "读取消息失败", e);
                    mCallback.onError("读取失败: " + e.getMessage());
                }
            } finally {
                if (DEBUG) Log.d(TAG, "ReaderThread 结束");
                closeQuietly();
            }
        }

        private void readMessage() throws IOException {
            // 读取 header (4 bytes)
            byte[] headerBytes = new byte[4];
            int read = mInputStream.read(headerBytes);
            if (read != 4) {
                throw new IOException("读取 header 失败, read=" + read);
            }

            ByteBuffer header = ByteBuffer.wrap(headerBytes);
            header.order(ByteOrder.BIG_ENDIAN);

            byte byte0 = header.get();
            byte byte1 = header.get();
            byte byte2 = header.get();
            byte byte3 = header.get();

            byte protocolVersion = (byte) (byte0 >> 4);
            byte headerSize = (byte) (byte0 & 0x0F);
            byte messageType = (byte) (byte1 >> 4);
            byte messageFlags = (byte) (byte1 & 0x0F);
            byte serialization = (byte) (byte2 >> 4);
            byte compression = (byte) (byte2 & 0x0F);

            if (DEBUG) {
                Log.v(TAG, String.format("收到消息: type=0x%X, flags=0x%X, serial=%d, compress=%d",
                        messageType, messageFlags, serialization, compression));
            }

            // 读取 sequence (如果 flags 表明有 sequence)
            int sequence = 0;
            if (messageFlags == FLAG_POSITIVE_SEQUENCE || messageFlags == FLAG_NEGATIVE_SEQUENCE) {
                byte[] seqBytes = new byte[4];
                mInputStream.readFully(seqBytes);
                ByteBuffer seqBuf = ByteBuffer.wrap(seqBytes);
                seqBuf.order(ByteOrder.BIG_ENDIAN);
                sequence = seqBuf.getInt();
            }

            // 读取 payload size (4 bytes)
            byte[] sizeBytes = new byte[4];
            mInputStream.readFully(sizeBytes);
            ByteBuffer sizeBuf = ByteBuffer.wrap(sizeBytes);
            sizeBuf.order(ByteOrder.BIG_ENDIAN);
            int payloadSize = sizeBuf.getInt();

            if (DEBUG) Log.v(TAG, "Payload size: " + payloadSize);

            // 读取 payload
            byte[] payload = new byte[payloadSize];
            mInputStream.readFully(payload);

            // 处理消息
            switch (messageType) {
                case MSG_TYPE_FULL_SERVER_RESPONSE:
                    handleServerResponse(payload, serialization, compression, messageFlags, sequence);
                    break;
                case MSG_TYPE_ERROR:
                    handleError(payload, serialization);
                    break;
                default:
                    if (DEBUG) Log.w(TAG, "未知消息类型: " + messageType);
                    break;
            }
        }

        private void handleServerResponse(byte[] payload, byte serialization,
                                          byte compression, byte flags, int sequence) {
            try {
                // 解压
                byte[] data = (compression == COMPRESSION_GZIP)
                        ? gzipDecompress(payload)
                        : payload;

                String json = new String(data, "UTF-8");
                if (DEBUG) Log.v(TAG, "服务器响应: " + json);

                // 解析 JSON
                String text = parseTranscript(json);
                boolean isFinal = (flags == FLAG_LAST_PACKET || flags == FLAG_NEGATIVE_SEQUENCE);

                if (text != null && !text.isEmpty()) {
                    mCallback.onTranscript(text, isFinal);
                }

            } catch (Exception e) {
                Log.e(TAG, "处理服务器响应失败", e);
            }
        }

        private void handleError(byte[] payload, byte serialization) {
            try {
                byte[] data = (serialization == SERIALIZATION_JSON)
                        ? gzipDecompress(payload)
                        : payload;

                String json = new String(data, "UTF-8");
                Log.e(TAG, "服务器错误: " + json);
                mCallback.onError("服务器错误: " + json);

            } catch (Exception e) {
                Log.e(TAG, "处理错误消息失败", e);
                mCallback.onError("未知错误");
            }
        }
    }

    /**
     * 解析识别结果
     */
    private String parseTranscript(String json) {
        // 简单 JSON 解析
        // 查找 "text": "xxx" 模式
        try {
            int textIndex = json.indexOf("\"text\"");
            if (textIndex == -1) return null;

            int colonIndex = json.indexOf(":", textIndex);
            if (colonIndex == -1) return null;

            int startQuote = json.indexOf("\"", colonIndex + 1);
            if (startQuote == -1) return null;

            int endQuote = json.indexOf("\"", startQuote + 1);
            if (endQuote == -1) return null;

            return json.substring(startQuote + 1, endQuote);

        } catch (Exception e) {
            if (DEBUG) Log.w(TAG, "解析 JSON 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * GZip 压缩
     */
    private byte[] gzipCompress(byte[] data) throws IOException {
        if (data.length == 0) return data;

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        GZIPOutputStream gzos = new GZIPOutputStream(baos);
        gzos.write(data);
        gzos.close();
        return baos.toByteArray();
    }

    /**
     * GZip 解压
     */
    private byte[] gzipDecompress(byte[] data) throws IOException {
        if (data.length == 0) return data;

        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
        GZIPInputStream gzis = new GZIPInputStream(bais);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        byte[] buffer = new byte[4096];
        int len;
        while ((len = gzis.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        gzis.close();
        return baos.toByteArray();
    }

    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return mIsConnected.get();
    }

    /**
     * 关闭连接
     */
    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        mIsConnected.set(false);
        mIsConnecting.set(false);

        if (mReaderThread != null) {
            mReaderThread.interrupt();
            mReaderThread = null;
        }

        try {
            if (mInputStream != null) {
                mInputStream.close();
                mInputStream = null;
            }
        } catch (IOException e) {
            // ignore
        }

        try {
            if (mOutputStream != null) {
                mOutputStream.close();
                mOutputStream = null;
            }
        } catch (IOException e) {
            // ignore
        }

        try {
            if (mSocket != null) {
                mSocket.close();
                mSocket = null;
            }
        } catch (IOException e) {
            // ignore
        }

        mCallback.onClosed();
        if (DEBUG) Log.d(TAG, "连接已关闭");
    }

    /**
     * 获取诊断信息
     */
    public String getDiagnosticInfo() {
        return "MiniMaxStreamingASRClient Diagnostics:\n"
                + "  isConnected: " + mIsConnected.get() + "\n"
                + "  isConnecting: " + mIsConnecting.get() + "\n"
                + "  connectId: " + mConnectId + "\n"
                + "  resourceId: " + mResourceId + "\n";
    }
}
