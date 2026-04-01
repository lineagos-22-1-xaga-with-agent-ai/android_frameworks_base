# Work Log

## 2026-04-01

### 调研：Framework 层集成 MiniMax AI Agent 控制手机

#### 用户需求
- AI Agent 能"看"手机屏幕（通过 SurfaceFlinger 获取帧buffer）
- 能分析屏幕内容并做出决策
- 能模拟用户操作（点击、滑动等）
- 场景：帮我点外卖等复杂任务

#### 核心技术组件

**1. 屏幕捕获 (ScreenCapture via SurfaceFlinger)**
```java
// 方式1: 捕获整个显示屏幕
ScreenCapture.ScreenshotHardwareBuffer buffer = 
    ScreenCapture.captureDisplay(new ScreenCapture.DisplayCaptureArgs.Builder(displayToken).build());
Bitmap bitmap = buffer.asBitmap();

// 方式2: 捕获特定 Layer
ScreenCapture.captureLayers(layerCaptureArgs, listener);
```
- `android.window.ScreenCapture` - 系统级屏幕捕获，走 SurfaceFlinger
- 无需 MediaProjection 授权
- 需要 `READ_FRAME_BUFFER` 权限

**2. 输入事件注入 (Input Injection)**
```java
// 注入触摸事件
InputManager.getInstance().injectInputEvent(motionEvent, 
    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);

// 或通过 shell command
InputManagerGlobal.getInstance().injectInputEvent(event, mode);
```
- `android.hardware.input.InputManager`
- 需要 `INJECT_EVENTS` 权限

**3. 无障碍服务 (Accessibility Service)**
- `AccessibilityService` - 获取界面元素信息
- 可模拟点击、滚动等操作
- 可获取窗口内容结构

**4. MiniMax API**
- Anthropic API 兼容模式（支持 function call）
- 支持流式输出和 thinking 过程
- 支持 204800 token 上下文

#### 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                   AI Agent Service                       │
│  (android.server.ai.AgentService)                       │
├─────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │
│  │ ScreenCapture│  │  MiniMax    │  │  DecisionEngine │ │
│  │(SurfaceFlinger)│  │   Client    │  │  (Function Call) │ │
│  └─────────────┘  └─────────────┘  └─────────────────┘ │
│         │                │                   │          │
│         ▼                ▼                   ▼          │
│  ┌─────────────────────────────────────────────────────┐│
│  │              Action Executor                       ││
│  │   (Input injection / AccessibilityService)          ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
```

#### 工具定义 (Function Calling)

Agent 需要暴露给 LLM 的工具：

1. **screenshot()** -> Bitmap
   - 使用 `ScreenCapture.captureDisplay()` 获取当前屏幕

2. **click(x, y)**
   - 使用 `InputManager.injectInputEvent()` 注入点击

3. **swipe(x1, y1, x2, y2, duration)**
   - 注入滑动 MotionEvent

4. **input_text(text)**
   - 输入文本

5. **get_element_info()**
   - 获取当前界面元素列表 (AccessibilityNodeInfo)

6. **launch_app(package_name)**
   - 启动指定应用

7. **press_back() / press_home()**
   - 按返回/主页键

#### 实现位置

```
frameworks/base/services/core/java/com/android/server/ai/
├── AgentService.java              # 主服务
├── AgentScreenCapture.java       # ScreenCapture 封装
├── AgentInputController.java     # 输入控制
├── MiniMaxClient.java            # MiniMax API 调用
└── AgentTools.java               # 工具定义
```

#### 屏幕捕获示例代码

```java
// 获取 DisplayToken
IBinder displayToken = display.getDisplayToken();
ScreenCapture.DisplayCaptureArgs captureArgs = 
    new ScreenCapture.DisplayCaptureArgs.Builder(displayToken)
        .setWidth(1080)  // 可选缩放
        .build();

ScreenshotHardwareBuffer buffer = ScreenCapture.captureDisplay(captureArgs);
if (buffer != null) {
    Bitmap bitmap = buffer.asBitmap();
    // 传给 MiniMax 分析
}
```

#### 权限要求

| 权限 | 说明 |
|------|------|
| `READ_FRAME_BUFFER` | 读取帧缓冲，用于截图 |
| `INJECT_EVENTS` | 注入输入事件（shell权限） |
| `ACCESSIBILITY_SERVICE` | 获取界面元素信息 |

#### MiniMax Function Call 示例

```json
{
  "tools": [{
    "name": "screenshot",
    "description": "Take a screenshot of the current screen",
    "input_schema": {"type": "object", "properties": {}}
  }, {
    "name": "click", 
    "description": "Click at the specified coordinates",
    "input_schema": {
      "type": "object",
      "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
      "required": ["x", "y"]
    }
  }]
}
```

#### 下一步
- [ ] 确认是否需要实现到 framework
- [ ] 决定使用 AccessibilityService 还是 InputManager 直接注入
- [ ] 确定 API Key 配置方式
- [ ] 设计 Agent Service 的 AIDL 接口
