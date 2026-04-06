# Work Log

## 2026-04-01

### 调研：Framework 层集成 MiniMax AI Agent 控制手机

#### 用户需求
- AI Agent 能"看"手机屏幕（通过 SurfaceFlinger 获取帧buffer）
- 能分析屏幕内容并做出决策
- 能模拟用户操作（点击、滑动等）
- 场景：帮我点外卖等复杂任务
- 支持定时任务、用户记忆、偏好学习等
- 支持 Skill 配置
- 支持语音唤醒交互

---

## 架构设计 (增强版 v2)

```
┌─────────────────────────────────────────────────────────────────┐
│                        AgentService                              │
│                  (SystemService, 负责生命周期管理)                  │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │   SkillRegistry │  │ VoiceWakeup    │  │  ConversationMgr│ │
│  │   (技能注册配置)   │  │ (语音唤醒)      │  │  (对话管理)      │ │
│  │  - skills.json   │  │  唤醒词检测      │  │  多轮对话上下文   │ │
│  │  - 动态加载      │  │  语音指令处理    │  │  状态跟踪       │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘ │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │   TaskScheduler │  │  UserMemory    │  │  ConversationCtx│ │
│  │   (定时任务调度)   │  │  (用户偏好记忆)   │  │  (多轮对话上下文) │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘ │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │  ScreenCapture  │  │   MiniMaxClient │  │   ActionExe     │ │
│  │ (SurfaceFlinger)│  │ (API调用+重试)  │  │ (InputManager)  │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘ │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                      ToolRegistry (19个工具)                ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

---

## 实现进度

### 已完成 ✅

**1. 服务文件 (9个)**
```
frameworks/base/services/core/java/com/android/server/ai/
├── AgentService.java           # 28KB - 主服务
├── AgentScreenCapture.java     # 4KB - 屏幕捕获
├── AgentInputController.java   # 7.5KB - 输入控制
├── MiniMaxClient.java          # 10KB - API客户端
├── AgentTools.java            # 36KB - 19个工具
├── UserMemoryManager.java     # 18KB - 用户记忆
├── TaskScheduler.java         # 16KB - 定时任务
├── SkillRegistry.java        # 18KB - 技能注册
└── VoiceWakeupManager.java   # 8KB - 语音唤醒
```

**2. Settings 常量**
- `Settings.Global.AGENT_SERVICE_ENABLED`
- `Settings.Global.MINIMAX_API_KEY`
- `Settings.Global.AGENT_VOICE_WAKEUP_ENABLED`
- `Settings.Global.AGENT_HOTWORD`

**3. SystemServer 注册**
- `import com.android.server.ai.AgentService`
- `startService(AgentService.class)`

**4. Git Commit**
```
0f1b63260 feat(agent): Add MiniMax AI Agent service with voice wakeup and skill system
```

---

## 工具函数 (Function Calling) - 19个

### 基础操作 (7个)
| 工具 | 功能 |
|------|------|
| `screenshot()` | 获取屏幕截图 |
| `click(x, y)` | 点击坐标 |
| `swipe(x1,y1,x2,y2,duration)` | 滑动 |
| `input_text(text)` | 输入文本 |
| `launch_app(package)` | 启动应用 |
| `press_back()` | 按返回键 |
| `press_home()` | 按主页键 |

### 记忆/偏好 (4个)
| 工具 | 功能 |
|------|------|
| `remember(key, value, category)` | 记忆存储 |
| `recall(key, category)` | 记忆召回 |
| `set_preference(category, key, value)` | 设置偏好 |
| `get_preference(category, key)` | 获取偏好 |

### 定时任务 (4个)
| 工具 | 功能 |
|------|------|
| `schedule_task(timestamp, task)` | 定时任务 |
| `schedule_recurring(interval_sec, task)` | 周期任务 |
| `cancel_task(task_id)` | 取消任务 |
| `get_pending_tasks()` | 获取待执行任务 |

### 上下文 (1个)
| 工具 | 功能 |
|------|------|
| `get_context()` | 获取屏幕/操作/对话历史 |

### Skill (3个)
| 工具 | 功能 |
|------|------|
| `list_skills()` | 列出所有可用技能 |
| `enable_skill(skill_id)` | 启用技能 |
| `disable_skill(skill_id)` | 禁用技能 |

---

## 默认 Skills (6个)

| Skill ID | 名称 | 触发词 |
|----------|------|--------|
| `order_food` | 点外卖 | 点外卖, 饿了, 叫外卖 |
| `check_schedule` | 查看日程 | 日程, schedule, 今天有什么 |
| `send_message` | 发消息 | 发消息, 发短信, 发微信 |
| `check_weather` | 查天气 | 天气, 今天天气, 明天天气 |
| `control_music` | 控制音乐 | 播放音乐, 暂停, 下一首 |
| `set_reminder` | 设置提醒 | 提醒我, 定时提醒, 闹钟 |

---

## 数据持久化

### SQLite 数据库

**agent_memory.db**
- `memories` - 用户记忆
- `preferences` - 用户偏好
- `conversations` - 对话历史
- `screen_history` - 屏幕历史 (最多10条)
- `action_history` - 操作历史 (最多50条)

**agent_tasks.db**
- `scheduled_tasks` - 定时任务

### JSON 配置

**/data/system/agent/skills.json**
- Skill 描述、触发词、权限
- 动态加载/更新

---

## 日志 TAG

| TAG | 说明 |
|-----|------|
| `AgentService` | 主服务 |
| `AgentScreenCapture` | 屏幕捕获 |
| `AgentInputController` | 输入控制 |
| `MiniMaxClient` | API调用 |
| `AgentTools` | 工具执行 |
| `UserMemoryManager` | 记忆管理 |
| `TaskScheduler` | 任务调度 |
| `SkillRegistry` | 技能注册 |
| `VoiceWakeup` | 语音唤醒 |

---

## 编译验证状态

**状态**: ⚠️ 需要完整 Android 编译环境

**问题**: 当前环境缺少 Android SDK 依赖，无法直接用 javac 编译

**需要**:
- 完整 Android build environment (bob/quiche/dalvik)
- 或使用 `brunch xaga userdebug` 进行完整编译

---

## 待完成

### 高优先级 ✅
- [x] **Settings UI 入口** - 在设置中添加 AI Agent 开关和配置界面 ✅
  - [x] 创建 `Settings > AI Agent` 页面
  - [x] 开关：启用/禁用服务
  - [x] API Key 配置输入框
  - [x] 语音唤醒开关
  - [x] 热词配置

- [ ] **权限声明** - 在 AndroidManifest.xml 中声明必要权限
  - `android.permission.INTERNET`
  - `android.permission.SYSTEM_ALERT_WINDOW` (悬浮窗)
  - `android.permission.GET_TASKS` (获取任务)
  - `android.permission.RECEIVE_BOOT_COMPLETED`
  - `android.permission.WAKE_LOCK`
  - `android.permission.RECORD_AUDIO` (语音唤醒)
  - `android.permission.CAPTURE_SCREENSHOT`

### 中优先级
- [ ] **测试验证**
  - 启动 AgentService 并验证日志
  - 测试 screenshot 工具
  - 测试 click/swipe 工具
  - 测试 API 调用流程

- [x] **AIDL 接口** - 创建真正的跨进程通信接口 ✅
  - [x] 定义 `IAgentService.aidl`
  - [x] 定义 `IAgentServiceCallback.aidl`
  - [x] 迁移 `AgentService` 到 AIDL Stub 并继续注册到 ServiceManager

### 低优先级
- [ ] Skill 动态加载测试
- [ ] 定时任务功能测试
- [ ] 用户记忆功能测试
- [ ] 语音唤醒功能集成

---

## 2026-04-01 Settings UI 实现记录

### 已完成 ✅

**Settings UI 文件 (5个 + XML + strings)**
```
packages/apps/Settings/src/com/android/settings/ai/
├── AgentSettings.java                    # 设置页面 Fragment
├── AgentServiceEnableController.java     # 服务启用开关控制器
├── AgentApiKeyController.java           # API Key 输入控制器
├── AgentVoiceWakeupController.java      # 语音唤醒开关控制器
└── AgentHotwordController.java          # 热词输入控制器

packages/apps/Settings/res/xml/agent_settings.xml  # Preference 布局
packages/apps/Settings/res/values/strings.xml      # 字符串资源
packages/apps/Settings/res/drawable/ic_settings_ai.xml  # AI Agent 图标
packages/apps/Settings/res/xml/system_dashboard_fragment.xml  # 添加 AI Agent 入口
```

**Settings 常量使用**
- `Settings.Global.AGENT_SERVICE_ENABLED` - 服务启用状态
- `Settings.Global.MINIMAX_API_KEY` - API Key
- `Settings.Global.AGENT_VOICE_WAKEUP_ENABLED` - 语音唤醒
- `Settings.Global.AGENT_HOTWORD` - 热词配置

---

## 2026-04-01 编译修复记录

### 修复的问题
1. **AgentTools.java** - 16个 `getInputSchema()` 方法添加 try-catch 处理 JSONException
2. **AgentScreenCapture.java** - 修复 `mScreenBounds` → `mScreenSize`, `setWidth/setHeight` → `setSize`, 使用反射获取 DisplayToken
3. **UiThread.getExecutor()** → `command -> UiThread.getHandler().post(command)` (5个文件)
4. **SkillRegistry.java** - 添加缺失的 `Looper` import
5. **VoiceWakeupManager.java** - 添加缺失的 `ContentResolver` import
6. **AgentService.java** - 简化回调处理，移除不必要的 RemoteException catch
7. **TaskScheduler.java** - 修复 executor 初始化

---

## 2026-04-02 运行时问题修复

### SELinux 策略问题 (04-02 12:20)

**错误**:
```
java.lang.SecurityException: SELinux denied for service.
```

**原因**: `AgentService` 使用名称 "agent" 注册为 binder 服务，但 SELinux 策略不允许 system_server 添加此服务。

**修复**:

1. **`system/sepolicy/private/service.te`** - 添加 `agent_service` 类型：
```te
type agent_service, system_server_service, service_manager_type;
```

2. **`system/sepolicy/private/system_server.te`** - 添加 add_service 规则：
```te
add_service(system_server, agent_service);
```

**注意**: 修改源文件后需要重新编译 sepolicy 才能生效：
```bash
m sepolicy
# 或完整编译
m
```

---

### 代码问题修复

| 文件 | 问题 | 修复内容 |
|------|------|---------|
| `VoiceWakeupManager.java` | `loadSettings()` 未调用 | 在构造函数中添加 `loadSettings()` 调用 |
| `SkillRegistry.java` | 文件 I/O 在 UiThread | 添加 `mFileIoExecutor` 专用线程执行器 |
| `AgentTools.java` | `GetContextTool` 异步回调未等待 | 添加 `CountDownLatch` 等待机制 |
| `AgentTools.java` | `RecallTool` 异步回调未等待 | 添加 `CountDownLatch` 等待机制 |
| `AgentTools.java` | `GetPreferenceTool` 异步回调未等待 | 添加 `CountDownLatch` 等待机制 |
| `AgentTools.java` | `getInputSchema()` 未捕获 JSONException | 添加 try-catch 处理 |
| `AgentService.java` | 未使用的导入 (`ActivityManager`, `Slog`) | 移除未使用的导入 |
| `AgentService.java` | callback null 检查缺失 | 添加 null 检查 |
| `AgentInputController.java` | `injectInputEvent` 返回类型错误 | `int` → `boolean`，`!= 0` → `!result` |
| `TaskScheduler.java` | `cancelTask` 竞态条件 | 添加 `synchronized` 块同步访问 |
| `TaskScheduler.java` | executor 实现不一致 | 统一使用 `command -> UiThread.getHandler().post(command)` |
| `AgentScreenCapture.java` | 变量名错误 (`mScreenBounds` → `mScreenSize`) | 已修复 |
| `MiniMaxClient.java` | 同步方法缺少同步锁 | 添加同步锁 |

---

### 权限说明

系统服务（如 `AgentService`）运行在 system 进程，拥有以下权限的隐式授权：
- `INTERNET` - 用于 MiniMax API 调用
- `WAKE_LOCK` - 用于保持设备唤醒
- `INJECT_EVENTS` - signature 级别，系统服务可持有
- `RECORD_AUDIO` - 用于语音唤醒
- `CAPTURE_SCREENSHOT` - 通过 SurfaceFlinger 截图

---

## 2026-04-02

### xaga 开机自启动 ADB 排查与修复

#### 现象
- 设备侧已配置开机默认打开 ADB，但主机端先后出现两类问题：
- `failed to open device: Access denied (insufficient permissions)`
- `adb: device unauthorized`

#### 排查结论
- 设备树里已有默认 USB ADB 配置：
  - [`device/xiaomi/xaga/init/init.xaga.rc`](/home/yy/root_dir/device/xiaomi/xaga/init/init.xaga.rc)
  - [`device/xiaomi/xaga/vendor.prop`](/home/yy/root_dir/device/xiaomi/xaga/vendor.prop)
- 第一阶段是宿主机 `udev` 缺少 `0e8d/2717` 规则，导致 USB 设备节点权限不足。
- 第二阶段 `unauthorized` 的根因不是设备没开 ADB，而是 `ro.adb.secure=0` 被错误地落到了 `vendor/build.prop`，而 `system/build.prop` 里仍然有 `ro.adb.secure=1`。
- `adbd` 在启动时只读取一次 `ro.adb.secure`，因此会出现运行时 `getprop ro.adb.secure` 为 `0`，但当前这次 `adbd` 仍按需授权模式运行的情况。

#### 关键产物验证
- `out/target/product/xaga/vendor/build.prop` 含 `ro.adb.secure=0`
- `out/target/product/xaga/system/build.prop` 含 `ro.adb.secure=1`
- 说明之前的覆盖位置不对，且系统分区内出现重复来源冲突

#### 修复方案
- 不再在 `device/xiaomi/xaga/device.mk` 中手动重复写入 `ro.adb.secure=0`
- 改为使用 Lineage 已支持的开关 `WITH_ADB_INSECURE := true`
- 放置位置：
  - [`device/xiaomi/xaga/lineage_xaga.mk`](/home/yy/root_dir/device/xiaomi/xaga/lineage_xaga.mk)
- 这样由 `vendor/lineage/config/common.mk` 统一生成唯一一条系统属性，避免 `duplicate sysprop assignments`

#### 最终修改
- [`device/xiaomi/xaga/lineage_xaga.mk`](/home/yy/root_dir/device/xiaomi/xaga/lineage_xaga.mk)：
  - 增加 `WITH_ADB_INSECURE := true`
- [`device/xiaomi/xaga/device.mk`](/home/yy/root_dir/device/xiaomi/xaga/device.mk)：
  - 删除本地手工 `ro.adb.secure=0` 覆盖

#### 后续验证建议
- 重新编译后确认：
  - `system/build.prop` 中只有一条 `ro.adb.secure=0`
  - 不再出现 duplicate sysprop 报错
- 刷机后验证：
  - 重启后 `adb devices -l` 不再回退到 `unauthorized`
  - `sys.usb.state` 为 `adb` 或 `mtp,adb`

---

### AgentService 开机崩溃排查与修复

#### 现象
- `system_server` 启动过程中崩溃，关键日志如下：
- `java.lang.RuntimeException: Failed to start service com.android.server.ai.AgentService: onStart threw an exception`
- `Caused by: java.lang.SecurityException: SELinux denied for service.`
- 同时伴随一条 `Outgoing transactions from this process must be FLAG_ONEWAY` 的 `AndroidRuntime`/`Binder` 警告

#### 排查结论
- 真正导致 `system_server` fatal 的原因不是 `FLAG_ONEWAY` 警告，而是 `AgentService.onStart()` 调用 `publishBinderService("agent", ...)` 时被 `servicemanager` 拒绝。
- `system/sepolicy/private/service.te` 中已经定义了 `agent_service`。
- `system/sepolicy/private/system_server.te` 中也已经存在 `add_service(system_server, agent_service)`。
- 但 `system/sepolicy/private/service_contexts` 中缺少服务名 `agent` 到 `u:object_r:agent_service:s0` 的映射。
- 因此 `ServiceManager.addService()` 无法为 `agent` 分配合法 SELinux service type，最终抛出 `SecurityException: SELinux denied for service`。

#### 关键代码点
- [`frameworks/base/services/java/com/android/server/SystemServer.java`](/home/yy/root_dir/frameworks/base/services/java/com/android/server/SystemServer.java)
  - `mSystemServiceManager.startService(AgentService.class);`
- [`frameworks/base/services/core/java/com/android/server/ai/AgentService.java`](/home/yy/root_dir/frameworks/base/services/core/java/com/android/server/ai/AgentService.java)
  - `publishBinderService(SERVICE_NAME, mBinder);`
  - `SERVICE_NAME = "agent"`
- [`system/sepolicy/private/service.te`](/home/yy/root_dir/system/sepolicy/private/service.te)
  - 已有 `type agent_service, system_server_service, service_manager_type;`
- [`system/sepolicy/private/system_server.te`](/home/yy/root_dir/system/sepolicy/private/system_server.te)
  - 已有 `add_service(system_server, agent_service);`
- [`system/sepolicy/private/service_contexts`](/home/yy/root_dir/system/sepolicy/private/service_contexts)
  - 原先缺失 `agent` 对应条目

#### 修复内容
- 在 [`system/sepolicy/private/service_contexts`](/home/yy/root_dir/system/sepolicy/private/service_contexts) 中新增：

```text
agent                                     u:object_r:agent_service:s0
```

#### 补充说明
- `FLAG_ONEWAY` 日志来自 `BinderProxy` 的 warn-on-blocking 检查，更像 userdebug/eng 构建下的告警，不是这次 `system_server` 崩溃的直接根因。
- 当时 `AgentService` 仍然使用手写 `Binder` `onTransact()` 暴露接口；该事项已在 2026-04-03 切换为 AIDL Stub 并补充 demo client。

#### 后续验证建议
- 重新编译 sepolicy 或完整系统镜像后验证：
  - `out/target/product/<product>/system/etc/selinux/plat_service_contexts` 中包含 `agent`
  - 设备启动后不再出现 `SELinux denied for service`
  - `AgentService` 能正常完成 `publishBinderService("agent", ...)`

---

### SettingsRoboTestStub 开机崩溃排查与修复

#### 现象
- `AgentService` 的 SELinux 注册问题处理后，新的 boot blocker 变为：
- `java.lang.IllegalStateException: Signature|privileged permissions not in privileged permission allowlist`
- 涉及包名：
  - `com.android.settings (/system/priv-app/SettingsRoboTestStub)`

#### 排查结论
- [`packages/apps/Settings/tests/robotests/Android.bp`](/home/yy/root_dir/packages/apps/Settings/tests/robotests/Android.bp) 中的 `SettingsRoboTestStub` 是给 Robolectric 用的测试 stub。
- 该模块定义为：
  - `android_app`
  - `certificate: "platform"`
  - `privileged: true`
- 但此前没有显式禁止安装，因此它被实际打进了镜像：
  - `/system/priv-app/SettingsRoboTestStub/SettingsRoboTestStub.apk`
- 该 stub 的 manifest 包名仍是 `com.android.settings`，因此系统把它当作特权 Settings 包处理，并在开机权限检查阶段因为未进入 `privapp-permissions` allowlist 而直接抛出 fatal。

#### 修复内容
- 在 [`packages/apps/Settings/tests/robotests/Android.bp`](/home/yy/root_dir/packages/apps/Settings/tests/robotests/Android.bp) 的 `SettingsRoboTestStub` 模块中新增：

```bp
installable: false,
```

#### 修复目的
- 保留 `SettingsRoboTestStub` 作为 Robolectric 测试依赖使用。
- 禁止其被安装到设备镜像，避免再次出现在 `/system/priv-app/SettingsRoboTestStub`。
- 避免 `com.android.settings` 的特权权限 allowlist 校验在开机阶段失败。

#### 补充说明
- 同一批日志里的 `Outgoing transactions from this process must be FLAG_ONEWAY` 仍然只是 `Binder` 警告，不是这次 fatal 的直接原因。
- 当前新的致命错误点已经从 `AgentService.onStart()` 切换到了权限系统的 `privapp-permissions` 校验阶段。

---

## 2026-04-03

### Agent AIDL 接口与命令行调用端实现

#### 已完成 ✅

**AIDL 文件**
```
frameworks/base/core/java/android/app/ai/
├── IAgentService.aidl
└── IAgentServiceCallback.aidl
```

**命令行调用端**
```
frameworks/base/cmds/agent/
├── Android.bp
├── agent.sh
└── src/com/android/commands/agent/Agent.java
```

#### 服务端改动

- [`frameworks/base/services/core/java/com/android/server/ai/AgentService.java`](/home/yy/root_dir/frameworks/base/services/core/java/com/android/server/ai/AgentService.java)
  - 移除手写 `Binder` `onTransact()`
  - 改为 `IAgentService.Stub`
  - `sendTask()` 支持通过 `IAgentServiceCallback` 回传：
    - `onThinking`
    - `onText`
    - `onToolResult`
    - `onComplete`
    - `onError`

#### AIDL 暴露能力

**IAgentService**
- `sendTask(String task, IAgentServiceCallback callback)`
- `isEnabled()`
- `setEnabled(boolean enabled)`
- `setApiKey(String apiKey)`
- `getApiKey()`
- `takeScreenshot()`
- `click(int x, int y)`
- `swipe(int x1, int y1, int x2, int y2, int duration)`
- `inputText(String text)`
- `pressBack()`
- `pressHome()`
- `launchApp(String packageName)`

**IAgentServiceCallback**
- `onThinking(String thinking)`
- `onText(String text)`
- `onToolResult(String toolName, Bundle result)`
- `onComplete()`
- `onError(String error)`

#### 命令行用法

编进系统后可直接通过 `adb shell agent ...` 调用：

```bash
adb shell agent status
adb shell agent enable true
adb shell agent set-api-key YOUR_KEY
adb shell agent send 帮我打开美团并点外卖
adb shell agent screenshot /data/local/tmp/agent.png
adb shell agent tap 500 1200
adb shell agent swipe 500 1600 500 400 300
adb shell agent text hello
adb shell agent back
adb shell agent home
adb shell agent launch com.meituan.android
```

#### 实现说明

- `IAgentServiceCallback` 使用 `oneway interface`
- `cmd agent send` 会等待回调完成，默认超时 120 秒
- 工具执行结果现在会通过 callback 返回给客户端
- 当前仍未实现“工具结果再次回灌模型继续推理”的完整多轮闭环，现状仍是单轮请求 + 本地执行工具

#### 验证状态

- 已完成代码级改造与 `git diff --check` 检查
- 尚未在完整 Android 编译环境和真机刷机环境做最终验证

---

## 2026-04-03 下午

### AI Agent 代码中文注释和日志添加

**修改文件 (9个服务文件)**
```
services/core/java/com/android/server/ai/
├── AgentService.java           # 中文注释 + Slog日志
├── AgentScreenCapture.java     # 中文注释 + Slog日志
├── AgentInputController.java   # 中文注释 + Slog日志
├── MiniMaxClient.java          # 中文注释 + Slog日志
├── AgentTools.java             # 中文注释 + Slog日志
├── UserMemoryManager.java      # 中文注释 + Slog日志
├── TaskScheduler.java          # 中文注释 + Slog日志
├── SkillRegistry.java          # 中文注释 + Slog日志
└── VoiceWakeupManager.java     # 中文注释 + Log日志

cmds/agent/src/com/android/commands/agent/Agent.java  # 中文注释 + Log日志
```

**主要变更**
- `Log` → `Slog`（Android系统服务标准）
- 每个方法添加中文 Javadoc 注释
- 关键逻辑处添加 `Slog.i/d/w/e` 日志
- 代码统计：+2581 行新代码

---

### agent 命令行工具修复

**问题**
- `adb shell agent` 报错：`/system/bin/sh: agent: inaccessible or not found`

**排查过程**
1. `out/target/product/xaga/system/bin/agent` - 文件存在 ✓
2. `out/target/product/xaga/system/framework/agent.jar` - 文件存在 ✓
3. zip 包中无 agent - 因为 zip 在修改 device.mk 之前就编译好了

**根因**
1. `agent/` 目录缺少构建系统必需文件：`MODULE_LICENSE_APACHE2`、`NOTICE`、`OWNERS`
2. `device.mk` 未添加 `agent` 到 `PRODUCT_PACKAGES`

**修复内容**

1. 添加许可证文件（从 `am` 模块复制）：
```bash
cp frameworks/base/cmds/am/MODULE_LICENSE_APACHE2 frameworks/base/cmds/agent/
cp frameworks/base/cmds/am/NOTICE frameworks/base/cmds/agent/
cp frameworks/base/cmds/am/OWNERS frameworks/base/cmds/agent/
```

2. `device/xiaomi/xaga/device.mk` 添加：
```mk
# AI Agent
PRODUCT_PACKAGES += \
    agent
```

---

### ADB Root 默认开启

**问题**
- Settings > Developer Options > Enable ADB root 默认关闭

**修改文件**
- `packages/modules/adb/root/adbroot_service.cpp:85`

**修改内容**
```cpp
// 修改前
ADBRootService::ADBRootService() : enabled_(false) {

// 修改后
ADBRootService::ADBRootService() : enabled_(true) {
```

**说明**
- 构造函数中 `enabled_` 默认值从 `false` 改为 `true`
- 重编译后 ADB root 将默认开启

---

## 2026-04-03 晚

### MiniMaxClient NetworkOnMainThreadException 修复

**错误**
```
java.lang.RuntimeException: Failed to start service...
android.os.NetworkOnMainThreadException
    at android.os.StrictMode$AndroidBlockGuardPolicy.onNetwork
    at java.net.Inet6AddressImpl.lookupHostByName
    ...
```

**根因**
- `MiniMaxClient` 使用 `UiThread.getHandler().post()` 提交网络任务
- `UiThread` 实际在主线程执行任务，导致网络请求在主线程触发

**修复文件**
- `frameworks/base/services/core/java/com/android/server/ai/MiniMaxClient.java`

**修复内容**
```java
// 之前 - 在主线程执行
private Executor mCallbackExecutor = command -> UiThread.getHandler().post(command);

// 之后 - 使用后台线程池执行
private final Executor mNetworkExecutor = Executors.newFixedThreadPool(2);
private Executor mCallbackExecutor = command -> mNetworkExecutor.execute(() -> {
    try {
        command.run();
    } catch (Exception e) {
        Slog.e(TAG, "Background execution error", e);
    }
});
```

---

### init.xaga.rc ime 关键字错误修复

**错误**
```
host_init_verifier: 50: Invalid keyword 'ime'
host_init_verifier: 51: Invalid keyword 'ime'
Failed to parse init scripts with 2 error(s).
```

**根因**
- `ime` 不是标准 init.rc 关键字，不能直接使用
- Android init.rc 中需要用 `cmd` 命令执行

**修复文件**
- `device/xiaomi/xaga/init/init.xaga.rc` (第 48-51 行)

**修复内容**
```rc
# 修改前
on property:sys.boot_completed=1
    ime enable com.sohu.inputmethod.sogou/.SogouIME
    ime set com.sohu.inputmethod.sogou/.SogouIME

# 修改后
on property:sys.boot_completed=1
    cmd ime enable com.sohu.inputmethod.sogou/.SogouIME
    cmd ime set com.sohu.inputmethod.sogou/.SogouIME
```

---

## 工厂镜像（Factory Image） vs 卡刷包

### 什么是工厂镜像

工厂镜像是 Google 为 Nexus/Pixel 设备提供的官方出厂镜像，包含：

```
├── bootloader-*.img       # 基带处理器固件
├── radio-*.img            # 无线电固件
├── boot.img
├── system.img
├── vendor.img
├── product.img
├── vbmeta.img
├── userdata.img
├── android-info.txt
└── flash-all.bat/sh      # 一键刷机脚本
```

**特点：**
- Google/OEM 官方签名
- 无需解锁 bootloader（官方镜像可用）
- 一键刷入所有分区
- 用途：恢复出厂系统

### 为什么 LineageOS 不提供工厂镜像

1. 没有设备制造商的私钥签名
2. 面向刷机用户，用 recovery 更灵活
3. 社区传统，继承自早期 Android 刷机习惯

### 如何生成线刷包

**方法1：直接 fastboot 烧写**

```bash
cd out/target/product/xaga/

fastboot flash system system.img
fastboot flash vendor vendor.img
fastboot flash product product.img
fastboot flash odm odm.img
fastboot flash boot boot.img
fastboot flash vbmeta vbmeta.img
fastboot erase userdata
fastboot -w
fastboot reboot
```

**方法2：生成 target files**

```bash
# 编译 target files
m targetfiles

# 位置：out/target/product/xaga/obj/PACKAGING/target_files_intermediates/
```

**方法3：MTK SP Flash Tool（xaga 使用 MTK 芯片）**

```bash
# 需要 scatter file，定义每个分区的起始地址和大小
# 从 out/target/product/xaga/ 提取各分区 img
# 用 SP Flash Tool 刷入
```

### 卡刷包 vs 工厂镜像

| | 工厂镜像 | 卡刷包 |
|---|---|---|
| 签名 | 官方签名 | 社区签名 |
| bootloader | 可不解锁 | 必须解锁 |
| 格式 | img + 脚本 | zip（recovery） |
| 厂商 | Google/OEM | 社区维护 |
| 用途 | 恢复出厂系统 | 刷入第三方 ROM |

---

## 2026-04-04

### 语音唤醒功能增强

#### 问题分析

设备（Redmi 22041216UC）没有预装热词模型：
- `Enrolled KeyphraseSoundModels: (No active implementation)`
- 没有找到 `.bin` 热词模型文件
- 系统虽有 `privapp-permissions-hotword.xml`，但无实际模型

#### 解决方案：按钮触发模式

由于设备没有热词模型，采用**长按音量下键**触发语音助手。

#### 新增文件

| 文件 | 行数 | 功能 |
|------|------|------|
| `AudioRecordManager.java` | 549 | 麦克风录音 + VAD 语音活动检测 |
| `MiniMaxASRClient.java` | 387 | MiniMax 语音识别 API 客户端 |
| `SoftWakeupDetector.java` | 570 | 软唤醒检测器（录音+ASR+唤醒词检测） |
| `VolumeKeyVoiceTrigger.java` | 233 | 音量键长按检测（0.5秒阈值） |
| `VoiceAssistActivity.java` | ~460 | 语音助手界面 + 系统 SpeechRecognizer |

#### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `PhoneWindowManager.java` | +18 行，集成 `VolumeKeyVoiceTrigger` |
| `VoiceWakeupManager.java` | +1000+ 行，软/硬唤醒支持 |
| `AgentService.java` | +100+ 行，集成 `VoiceWakeupManager` |
| `AndroidManifest.xml` | +25 行，注册 `VoiceAssistActivity` |
| `current.txt` | +15 行，添加 `android.app.ai.VoiceAssistActivity` API |

#### 工作流程

```
用户：长按音量下键 0.5 秒
    ↓
PhoneWindowManager 拦截按键
    ↓
VolumeKeyVoiceTrigger 检测到长按
    ↓
发送 ACTION_VOICE_ASSIST Intent
    ↓
VoiceAssistActivity 启动
    ↓
显示录音界面，自动开始语音识别
    ↓
用户说话 → 系统 SpeechRecognizer 识别
    ↓
识别结果发送给 AgentService
    ↓
显示 AI 响应
    ↓
3秒后自动关闭
```

#### 关键代码位置

**PhoneWindowManager.java**
- 第 538 行：`mVolumeKeyVoiceTrigger` 成员变量
- 第 2573-2574 行：初始化 `VolumeKeyVoiceTrigger`
- 第 5747-5754 行：音量键处理中调用触发器

**VoiceAssistActivity.java**
- 响应 `ACTION_VOICE_ASSIST` 和 `ACTION_ASSIST` Intent
- 使用系统 `SpeechRecognizer` 进行语音识别
- 绑定 `AgentService` 发送识别结果

#### 待完成

- [ ] 编译验证
- [ ] 真机测试音量键长按触发
- [ ] 语音识别功能验证
- [ ] AI Agent 处理流程验证

#### 后续优化方向

1. **唤醒词模型** - 如果能获取到热词模型文件，可以支持"隔空唤醒"
2. **MiniMax ASR** - 目前使用系统 SpeechRecognizer，可选切换到 MiniMax API
3. **UI 优化** - 目前是简单 demo UI，可优化为更美观的界面
