# Work Log

## 2026-04-01

### 调研：Framework 层集成 MiniMax AI Agent 控制手机

#### 用户需求
- AI Agent 能"看"手机屏幕（通过 SurfaceFlinger 获取帧buffer）
- 能分析屏幕内容并做出决策
- 能模拟用户操作（点击、滑动等）
- 场景：帮我点外卖等复杂任务

#### 确认决定
1. **InputManager** - 用于输入事件注入
2. **API Key** - 存储在 `Settings.Global.MINIMAX_API_KEY`
3. **不实现** - 调研阶段

#### 实现完成

**创建的文件：**
```
frameworks/base/services/core/java/com/android/server/ai/
├── AgentInputController.java   # 输入控制 (InputManager 封装)
├── AgentScreenCapture.java     # 屏幕捕获 (ScreenCapture 封装)
├── AgentService.java           # 主服务
├── AgentTools.java            # 工具定义 (Function Calling)
└── MiniMaxClient.java         # MiniMax API 调用
```

**修改的文件：**
```
frameworks/base/core/java/android/provider/Settings.java
```
- 添加 `Global.AGENT_SERVICE_ENABLED`
- 添加 `Global.MINIMAX_API_KEY`

#### 工具函数 (Function Calling)
| 工具 | 功能 |
|------|------|
| `screenshot()` | 获取屏幕截图 |
| `click(x, y)` | 点击坐标 |
| `swipe(x1,y1,x2,y2,duration)` | 滑动 |
| `input_text(text)` | 输入文本 |
| `launch_app(package)` | 启动应用 |
| `press_back()` | 按返回键 |
| `press_home()` | 按主页键 |

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
│  │   (InputManager.injectInputEvent)                 ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
```

#### 工作流程
```
截图 → Base64 → MiniMax 视觉理解 → LLM 决策 → InputManager 执行操作
```

#### 待完成
- [ ] 注册 AgentService 到 SystemServiceRegistry
- [ ] 添加 Settings UI 入口配置 API Key
- [ ] 添加权限声明
- [ ] 测试验证
