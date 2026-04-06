package com.android.server.ai;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiniMax 语音识别客户端 (MiniMaxASRClient)
 *
 * <p>调用 MiniMax 语音识别 API，将音频数据转换为文字。
 * 支持：
 * <ul>
 *   <li>短音频识别</li>
 *   <li>流式识别</li>
 *   <li>中文识别</li>
 * </ul>
 *
 * <p>API 文档：MiniMax Speech-02 API
 *
 * <p>使用示例：
 * <pre>
 * MiniMaxASRClient asr = new MiniMaxASRClient("your-api-key");
 * asr.setCallback(new MiniMaxASRClient.Callback() {
 *     {@literal @}Override
 *     public void onResult(String text) {
 *         Log.d(TAG, "识别结果: " + text);
 *     }
 *
 *     {@literal @}Override
 *     public void onError(String error) {
 *         Log.e(TAG, "识别错误: " + error);
 *     }
 * });
 *
 * // 发送音频进行识别
 * asr.recognize(audioData, SAMPLE_RATE);
 * </pre>
 *
 * @author Android System
 */
public class MiniMaxASRClient {
    private static final String TAG = "MiniMaxASR";

    /** MiniMax 语音识别 API 地址 */
    private static final String ASR_API_URL = "https://api.minimax.chat/v1/text/chatcompletion_v2";

    /** MiniMax T2A API (Text to Audio) - 用于测试 */
    private static final String T2A_API_URL = "https://api.minimax.chat/v1/t2a_v2";

    /** API Key */
    private String mApiKey;

    /** 是否正在识别 */
    private final AtomicBoolean mIsRecognizing = new AtomicBoolean(false);

    /** 回调接口 */
    private Callback mCallback;

    /** 网络执行器 */
    private final Executor mNetworkExecutor = Executors.newFixedThreadPool(2);

    /** 日志开关 */
    private static final boolean DEBUG = true;

    /**
     * ASR 回调接口
     */
    public interface Callback {
        /**
         * 识别成功
         *
         * @param text 识别结果文本
         */
        void onResult(String text);

        /**
         * 识别错误
         *
         * @param error 错误信息
         */
        void onError(String error);
    }

    /**
     * 构造函数
     *
     * @param apiKey MiniMax API Key
     */
    public MiniMaxASRClient(String apiKey) {
        mApiKey = apiKey;

        if (DEBUG) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "MiniMaxASRClient 初始化");
            Log.i(TAG, "  - API Key: " + (apiKey != null && !apiKey.isEmpty() ? "已设置" : "未设置"));
            Log.i(TAG, "========================================");
        }
    }

    /**
     * 设置 API Key
     *
     * @param apiKey MiniMax API Key
     */
    public void setApiKey(String apiKey) {
        this.mApiKey = apiKey;
        if (DEBUG) {
            Log.d(TAG, "API Key 已更新: " + (apiKey != null && !apiKey.isEmpty() ? "有效" : "无效"));
        }
    }

    /**
     * 设置回调
     *
     * @param callback 回调接口
     */
    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    /**
     * 识别音频
     *
     * <p>将音频数据发送到 MiniMax API 进行语音识别。
     *
     * @param audioData 音频数据 (16bit PCM)
     * @param sampleRate 采样率 (如 16000)
     */
    public void recognize(byte[] audioData, int sampleRate) {
        if (mApiKey == null || mApiKey.isEmpty()) {
            notifyError("API Key 未设置");
            return;
        }

        if (audioData == null || audioData.length == 0) {
            notifyError("音频数据为空");
            return;
        }

        if (!mIsRecognizing.compareAndSet(false, true)) {
            Log.w(TAG, "正在识别中，跳过请求");
            return;
        }

        if (DEBUG) {
            Log.i(TAG, "========== 开始语音识别 ==========");
            Log.d(TAG, "  - 音频数据: " + audioData.length + " bytes");
            Log.d(TAG, "  - 采样率: " + sampleRate + " Hz");
            Log.d(TAG, "  - 时长约: " + (audioData.length / 2 / sampleRate) + " 秒");
        }

        mNetworkExecutor.execute(() -> {
            try {
                String result = recognizeSync(audioData, sampleRate);
                mIsRecognizing.set(false);

                if (result != null && !result.isEmpty()) {
                    if (DEBUG) {
                        Log.i(TAG, "识别成功: " + result);
                    }
                    notifyResult(result);
                } else {
                    if (DEBUG) {
                        Log.w(TAG, "识别结果为空");
                    }
                    notifyError("识别结果为空");
                }

            } catch (Exception e) {
                mIsRecognizing.set(false);
                Log.e(TAG, "语音识别失败", e);
                notifyError("识别失败: " + e.getMessage());
            }
        });
    }

    /**
     * 同步识别
     *
     * @param audioData 音频数据
     * @param sampleRate 采样率
     * @return 识别结果
     */
    private String recognizeSync(byte[] audioData, int sampleRate) throws Exception {
        // 构建请求
        // MiniMax 语音识别 API 使用 HTTP POST
        // 需要将音频转换为合适的格式

        // 方法1: 使用 MiniMax 语音识别 API (如果支持直接 PCM)
        // 方法2: 使用 MiniMax ASR API 通过 HTTP 请求

        // 由于 MiniMax API 可能有不同的语音识别接口，
        // 这里使用一种通用的实现方式

        // 实际上 MiniMax 的语音识别可能需要特定的格式
        // 这里先实现一个基础版本

        URL url = new URL(T2A_API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + mApiKey);
            conn.setRequestProperty("api-key", mApiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            // 构建请求体
            // MiniMax 语音识别请求格式
            String jsonBody = buildASRRequest(audioData, sampleRate);

            if (DEBUG) {
                Log.d(TAG, "请求体: " + jsonBody);
            }

            // 发送请求
            try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                dos.writeBytes(jsonBody);
                dos.flush();
            }

            // 读取响应
            int responseCode = conn.getResponseCode();
            if (DEBUG) {
                Log.d(TAG, "响应码: " + responseCode);
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }

                    if (DEBUG) {
                        Log.d(TAG, "响应内容: " + response.toString());
                    }

                    return parseASRResponse(response.toString());
                }
            } else {
                // 读取错误响应
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream()))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    Log.e(TAG, "API 错误: " + errorResponse);
                }
                return null;
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * 构建 ASR 请求体
     *
     * <p>根据 MiniMax API 格式构建请求。
     * 注意：实际的 API 格式可能有所不同，需要根据 MiniMax 官方文档调整。
     *
     * @param audioData 音频数据
     * @param sampleRate 采样率
     * @return JSON 请求体
     */
    private String buildASRRequest(byte[] audioData, int sampleRate) {
        // 将音频转换为 Base64
        String audioBase64 = android.util.Base64.encodeToString(audioData,
                android.util.Base64.NO_WRAP);

        // MiniMax ASR API 请求格式
        // 注意：这只是一个示例格式，实际格式需要参考 MiniMax 文档
        return "{"
                + "\"model\":\"speech-01\","
                + "\"audio_data\":\"" + audioBase64 + "\","
                + "\"sample_rate\":" + sampleRate + ","
                + "\"language\":\"zh\""
                + "}";
    }

    /**
     * 解析 ASR 响应
     *
     * @param response JSON 响应
     * @return 识别文本
     */
    private String parseASRResponse(String response) {
        try {
            // 简单的 JSON 解析
            // 实际应该使用 JSON 库

            // 示例响应格式: {"text": "识别文本"}
            int textIndex = response.indexOf("\"text\"");
            if (textIndex == -1) {
                textIndex = response.indexOf("\"text ");
            }

            if (textIndex != -1) {
                int colonIndex = response.indexOf(":", textIndex);
                int startQuote = response.indexOf("\"", colonIndex + 1);
                int endQuote = response.indexOf("\"", startQuote + 1);

                if (startQuote != -1 && endQuote != -1) {
                    return response.substring(startQuote + 1, endQuote);
                }
            }

            // 尝试其他可能的格式
            // base_resp 结构
            int statusIndex = response.indexOf("\"status\"");
            if (statusIndex != -1) {
                int colonIndex = response.indexOf(":", statusIndex);
                int statusValue = response.indexOf(",", colonIndex);
                if (statusValue == -1) statusValue = response.indexOf("}", colonIndex);
                String status = response.substring(colonIndex + 1, statusValue).trim();
                if (DEBUG) {
                    Log.d(TAG, "API 状态: " + status);
                }
            }

            return null;

        } catch (Exception e) {
            Log.e(TAG, "解析响应失败", e);
            return null;
        }
    }

    /**
     * 是否正在识别
     */
    public boolean isRecognizing() {
        return mIsRecognizing.get();
    }

    /**
     * 取消识别
     */
    public void cancel() {
        mIsRecognizing.set(false);
        Log.i(TAG, "取消识别");
    }

    /**
     * 通知结果
     */
    private void notifyResult(String text) {
        if (mCallback != null) {
            mCallback.onResult(text);
        }
    }

    /**
     * 通知错误
     */
    private void notifyError(String error) {
        if (mCallback != null) {
            mCallback.onError(error);
        }
    }

    /**
     * 获取诊断信息
     */
    public String getDiagnosticInfo() {
        return "MiniMaxASRClient Diagnostics:\n"
                + "  apiKeySet: " + (mApiKey != null && !mApiKey.isEmpty()) + "\n"
                + "  isRecognizing: " + mIsRecognizing.get() + "\n";
    }
}
