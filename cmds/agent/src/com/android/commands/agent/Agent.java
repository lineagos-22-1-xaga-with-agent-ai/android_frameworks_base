/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.commands.agent;

import android.app.ai.IAgentService;
import android.app.ai.IAgentServiceCallback;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.os.BaseCommand;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent命令行工具
 *
 * 这是一个用于与Android Agent服务交互的命令行工具。通过该工具，用户可以：
 * - 查看和修改Agent服务的状态
 * - 配置API密钥
 * - 发送任务给Agent处理
 * - 执行屏幕操作（截图、点击、滑动等）
 * - 模拟物理按键（返回、主页）
 * - 启动应用程序
 *
 * 使用方法：
 * <pre>
 * agent &lt;command&gt; [args]
 * </pre>
 *
 * 支持的命令包括：
 * - status: 查看服务状态
 * - enable: 启用/禁用服务
 * - set-api-key/get-api-key: 管理API密钥
 * - send: 发送任务文本
 * - screenshot: 屏幕截图
 * - tap/swipe: 触摸操作
 * - text: 输入文本
 * - back/home: 按键操作
 * - launch: 启动应用
 *
 * @author Android System
 */
public final class Agent extends BaseCommand {
    /** 日志标签 */
    private static final String TAG = "AgentCommand";
    /** Agent服务名称 */
    private static final String SERVICE_NAME = "agent";
    /** 发送任务超时时间（秒） */
    private static final long SEND_TIMEOUT_SECONDS = 120;

    /** Agent服务代理接口 */
    private IAgentService mAgentService;

    /**
     * 命令行入口点
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        Log.i(TAG, "Agent命令行工具启动");
        new Agent().run(args);
    }

    /**
     * 显示使用帮助
     *
     * @param out 输出流，用于写入帮助信息
     */
    @Override
    public void onShowUsage(PrintStream out) {
        out.println("agent - Android Agent服务命令行工具");
        out.println();
        out.println("用法: agent <命令> [参数]");
        out.println();
        out.println("可用命令:");
        out.println("  status              - 查看Agent服务状态");
        out.println("  enable <true|false> - 启用或禁用Agent服务");
        out.println("  set-api-key <key>   - 设置API密钥");
        out.println("  get-api-key         - 获取当前API密钥");
        out.println("  send <任务文本>      - 发送任务给Agent处理");
        out.println("  screenshot [输出文件] - 截取屏幕截图");
        out.println("  tap <x> <y>         - 点击指定坐标位置");
        out.println("  swipe <x1> <y1> <x2> <y2> [duration_ms] - 执行滑动操作");
        out.println("  text <文本>          - 输入文本内容");
        out.println("  back                - 模拟按下返回键");
        out.println("  home                - 模拟按下主页键");
        out.println("  launch <包名>        - 启动指定应用程序");

        Log.d(TAG, "显示帮助信息");
    }

    /**
     * 执行命令的主方法
     *
     * 解析命令行参数并分发到相应的处理方法。
     *
     * @throws Exception 如果执行过程中发生错误
     */
    @Override
    public void onRun() throws Exception {
        Log.d(TAG, "开始执行Agent命令");

        // 获取Agent服务代理
        mAgentService = IAgentService.Stub.asInterface(ServiceManager.getService(SERVICE_NAME));
        if (mAgentService == null) {
            Log.e(TAG, "无法获取Agent服务代理");
            showError("错误: 无法访问Agent服务，系统是否正在运行？");
            return;
        }

        Log.d(TAG, "成功获取Agent服务代理");

        // 获取并解析命令
        final String command = nextArgRequired();
        Log.i(TAG, "执行命令: " + command);

        switch (command) {
            case "status":
                runStatus();
                return;
            case "enable":
                runEnable();
                return;
            case "set-api-key":
                runSetApiKey();
                return;
            case "get-api-key":
                runGetApiKey();
                return;
            case "send":
                runSend();
                return;
            case "screenshot":
                runScreenshot();
                return;
            case "tap":
                runTap();
                return;
            case "swipe":
                runSwipe();
                return;
            case "text":
                runText();
                return;
            case "back":
                printActionResult("back", mAgentService.pressBack());
                return;
            case "home":
                printActionResult("home", mAgentService.pressHome());
                return;
            case "launch":
                runLaunch();
                return;
            default:
                Log.w(TAG, "未知命令: " + command);
                throw new IllegalArgumentException("未知命令: " + command);
        }
    }

    /**
     * 查看Agent服务状态
     *
     * 显示服务的当前状态、API密钥配置情况等信息。
     *
     * @throws RemoteException 如果与服务通信失败
     */
    private void runStatus() throws RemoteException {
        Log.d(TAG, "查询服务状态");
        final String apiKey = mAgentService.getApiKey();
        System.out.println("service=" + SERVICE_NAME);
        System.out.println("enabled=" + mAgentService.isEnabled());
        System.out.println("apiKeyConfigured=" + (apiKey != null && !apiKey.isEmpty()));
        Log.i(TAG, "状态查询完成 - enabled=" + mAgentService.isEnabled());
    }

    /**
     * 设置Agent服务启用状态
     *
     * @throws RemoteException 如果与服务通信失败
     */
    private void runEnable() throws RemoteException {
        final boolean enabled = Boolean.parseBoolean(nextArgRequired());
        Log.d(TAG, "设置服务启用状态: " + enabled);
        mAgentService.setEnabled(enabled);
        System.out.println("enabled=" + mAgentService.isEnabled());
        Log.i(TAG, "服务启用状态已设置为: " + enabled);
    }

    /**
     * 设置API密钥
     *
     * @throws RemoteException 如果与服务通信失败
     */
    private void runSetApiKey() throws RemoteException {
        final String apiKey = nextArgRequired();
        Log.d(TAG, "设置API密钥");
        mAgentService.setApiKey(apiKey);
        System.out.println("apiKeyConfigured=true");
        Log.i(TAG, "API密钥配置完成");
    }

    /**
     * 获取当前配置的API密钥
     *
     * @throws RemoteException 如果与服务通信失败
     */
    private void runGetApiKey() throws RemoteException {
        Log.d(TAG, "获取API密钥");
        final String apiKey = mAgentService.getApiKey();
        System.out.println(apiKey == null ? "" : apiKey);
        Log.i(TAG, "API密钥获取完成");
    }

    /**
     * 发送任务给Agent处理
     *
     * 将任务文本发送给Agent服务，并注册回调监听器来处理
     * Agent返回的思考过程、文本输出、工具执行结果等信息。
     *
     * @throws Exception 如果任务执行超时或失败
     */
    private void runSend() throws Exception {
        final String task = joinRemainingArgs();
        if (task.isEmpty()) {
            Log.w(TAG, "任务文本为空");
            throw new IllegalArgumentException("任务不能为空");
        }

        Log.i(TAG, "发送任务: " + task);

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicReference<String> errorMessage = new AtomicReference<>(null);

        // 创建回调监听器
        mAgentService.sendTask(task, new IAgentServiceCallback.Stub() {
            /**
             * 处理Agent思考中的输出
             */
            @Override
            public void onThinking(String thinking) {
                if (thinking != null && !thinking.isEmpty()) {
                    System.out.println("[thinking] " + thinking);
                    Log.d(TAG, "Agent思考: " + thinking);
                }
            }

            /**
             * 处理Agent返回的文本输出
             */
            @Override
            public void onText(String text) {
                if (text != null && !text.isEmpty()) {
                    System.out.println("[text] " + text);
                    Log.d(TAG, "Agent文本输出: " + text);
                }
            }

            /**
             * 处理Agent工具执行结果
             */
            @Override
            public void onToolResult(String toolName, Bundle result) {
                System.out.println("[tool] " + toolName + " -> " + bundleToString(result));
                Log.d(TAG, "工具执行结果: " + toolName);
            }

            /**
             * 处理Agent任务完成回调
             */
            @Override
            public void onComplete() {
                System.out.println("[complete]");
                Log.i(TAG, "Agent任务完成");
                finished.set(true);
                done.countDown();
            }

            /**
             * 处理Agent错误回调
             */
            @Override
            public void onError(String error) {
                System.err.println("[error] " + error);
                Log.e(TAG, "Agent执行错误: " + error);
                errorMessage.set(error);
                done.countDown();
            }
        });

        Log.d(TAG, "等待Agent响应...");

        // 等待任务完成，设置超时时间
        if (!done.await(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.e(TAG, "Agent响应超时");
            throw new IllegalStateException("等待Agent响应超时");
        }

        if (!finished.get()) {
            String error = errorMessage.get();
            Log.e(TAG, "任务执行失败: " + error);
            throw new IllegalStateException("任务执行失败: " + (error != null ? error : "未知错误"));
        }

        Log.i(TAG, "任务执行成功完成");
    }

    /**
     * 执行屏幕截图
     *
     * 截取当前屏幕内容。如果指定了输出文件路径，则保存为PNG格式；
     * 否则只输出屏幕尺寸信息。
     *
     * @throws RemoteException 如果与服务通信失败
     * @throws IOException 如果写入文件失败
     */
    private void runScreenshot() throws RemoteException, IOException {
        Log.d(TAG, "执行屏幕截图");

        final Bitmap bitmap = mAgentService.takeScreenshot();
        if (bitmap == null) {
            Log.e(TAG, "截图失败，返回null");
            throw new IllegalStateException("截图失败");
        }

        Log.i(TAG, "截图成功，尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());

        // 获取输出路径参数
        final String output = nextArg();
        if (output == null) {
            // 没有指定输出路径，只输出尺寸信息
            System.out.println("width=" + bitmap.getWidth());
            System.out.println("height=" + bitmap.getHeight());
            Log.d(TAG, "未指定输出文件，仅输出尺寸信息");
            return;
        }

        // 保存截图到文件
        try (FileOutputStream fos = new FileOutputStream(output)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        System.out.println("saved=" + output);
        Log.i(TAG, "截图已保存到: " + output);
    }

    /**
     * 执行点击操作
     *
     * 在屏幕指定坐标位置执行模拟点击操作。
     *
     * @throws RemoteException 如果与服务通信失败
     */
    private void runTap() throws RemoteException {
        final int x = Integer.parseInt(nextArgRequired());
        final int y = Integer.parseInt(nextArgRequired());
        Log.d(TAG, "执行点击操作: (" + x + ", " + y + ")");
        printActionResult("tap", mAgentService.click(x, y));
        Log.i(TAG, "点击操作完成");
    }

    /**
     * 执行滑动操作
     *
     * 在屏幕指定起点和终点之间执行滑动操作。
     * 可选参数duration指定滑动持续时间（毫秒），默认为300ms。
     *
     * @throws RemoteException 如果与服务通信失败
     */
    private void runSwipe() throws RemoteException {
        final int x1 = Integer.parseInt(nextArgRequired());
        final int y1 = Integer.parseInt(nextArgRequired());
        final int x2 = Integer.parseInt(nextArgRequired());
        final int y2 = Integer.parseInt(nextArgRequired());
        final String durationArg = nextArg();
        final int duration = durationArg == null ? 300 : Integer.parseInt(durationArg);

        Log.d(TAG, "执行滑动操作: (" + x1 + ", " + y1 + ") -> (" + x2 + ", " + y2 + ") 持续时间: " + duration + "ms");
        printActionResult("swipe", mAgentService.swipe(x1, y1, x2, y2, duration));
        Log.i(TAG, "滑动操作完成");
    }

    /**
     * 输入文本内容
     *
     * 模拟键盘输入，将指定文本内容输入到当前焦点窗口。
     *
     * @throws RemoteException 如果与服务通信失败
     */
    private void runText() throws RemoteException {
        final String text = joinRemainingArgs();
        if (text.isEmpty()) {
            Log.w(TAG, "输入文本为空");
            throw new IllegalArgumentException("文本不能为空");
        }
        Log.d(TAG, "输入文本: " + text);
        printActionResult("text", mAgentService.inputText(text));
        Log.i(TAG, "文本输入完成");
    }

    /**
     * 启动指定应用程序
     *
     * 根据包名启动对应的Android应用程序。
     *
     * @throws RemoteException 如果与服务通信失败
     */
    private void runLaunch() throws RemoteException {
        final String packageName = nextArgRequired();
        Log.d(TAG, "启动应用: " + packageName);
        printActionResult("launch", mAgentService.launchApp(packageName));
        Log.i(TAG, "应用启动命令已发送");
    }

    /**
     * 打印操作结果
     *
     * 将操作结果以标准格式输出到控制台。
     *
     * @param action 操作名称
     * @param success 操作是否成功
     */
    private void printActionResult(String action, boolean success) {
        System.out.println(action + "=" + (success ? "ok" : "failed"));
        Log.d(TAG, "操作结果: " + action + "=" + (success ? "成功" : "失败"));
    }

    /**
     * 连接剩余的所有参数
     *
     * 将所有剩余的命令行参数连接成一个字符串，参数之间用空格分隔。
     * 用于处理包含空格的文本输入。
     *
     * @return 连接后的完整字符串
     */
    private String joinRemainingArgs() {
        final StringBuilder builder = new StringBuilder();
        String arg;
        while ((arg = nextArg()) != null) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(arg);
        }
        Log.d(TAG, "参数连接结果: " + builder.toString());
        return builder.toString();
    }

    /**
     * 将Bundle对象转换为字符串表示
     *
     * 用于在日志和输出中展示Bundle的内容。
     * 会对键进行排序以保证输出的一致性。
     *
     * @param bundle 要转换的Bundle对象
     * @return Bundle的字符串表示，格式为{k1=v1, k2=v2}
     */
    private String bundleToString(Bundle bundle) {
        if (bundle == null || bundle.isEmpty()) {
            return "{}";
        }

        final List<String> keys = new ArrayList<>(bundle.keySet());
        Collections.sort(keys);

        final StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(key).append('=').append(String.valueOf(bundle.get(key)));
        }
        builder.append('}');
        Log.v(TAG, "Bundle转换: " + builder.toString());
        return builder.toString();
    }
}
