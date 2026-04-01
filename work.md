# Work Log

## 2026-04-01

### 调研：Framework 层集成 MiniMax AI Agent 控制手机

#### 用户需求
- AI Agent 能"看"手机屏幕（通过 SurfaceFlinger 获取帧buffer）
- 能分析屏幕内容并做出决策
- 能模拟用户操作（点击、滑动等）
- 场景：帮我点外卖等复杂任务
- 支持定时任务、用户记忆、偏好学习等
- **支持 Skill 配置**
- **支持语音唤醒交互**

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
│  │  - skill.json   │  │  唤醒词检测      │  │  多轮对话上下文   │ │
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
│  │                      ToolRegistry (15个工具)                ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

---

## 核心模块 (新增/增强)

### 1. SkillRegistry (技能注册配置)
```json
// /data/system/agent/skills.json
{
  "skills": [
    {
      "id": "order_food",
      "name": "点外卖",
      "description": "帮我点外卖，选择最优惠的选项",
      "trigger": ["点外卖", "饿了", "叫外卖"],
      "enabled": true,
      "permission": ["camera", "storage"]
    },
    {
      "id": "check_schedule", 
      "name": "查看日程",
      "description": "查看和管理日程安排",
      "trigger": ["日程", " schedule", "今天有什么"],
      "enabled": true
    }
  ]
}
```
- Skill 描述、触发词、权限
- 动态加载/更新
- 按需启用/禁用

### 2. VoiceWakeup (语音唤醒)
- 集成 `VoiceInteractionService`
- 自定义唤醒词检测
- 语音指令识别 → Task
- 持续监听模式 / 点按唤醒模式

### 3. ConversationManager (对话管理)
- 多轮对话状态机
- Skill 上下文保持
- 主动询问 / 确认流程

---

## 工具函数 (Function Calling) - 完整列表

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

### Skill 相关 (新增)
| 工具 | 功能 |
|------|------|
| `list_skills()` | 列出所有可用技能 |
| `enable_skill(skill_id)` | 启用技能 |
| `disable_skill(skill_id)` | 禁用技能 |
| `execute_skill(skill_id, params)` | 执行技能 |

---

## 实现进度

### 步骤 1: 创建服务文件 ✅
```
frameworks/base/services/core/java/com/android/server/ai/
├── AgentService.java           # 主服务
├── AgentScreenCapture.java     # 屏幕捕获
├── AgentInputController.java   # 输入控制
├── MiniMaxClient.java          # MiniMax API
├── AgentTools.java            # 工具定义
├── UserMemoryManager.java     # 用户记忆
└── TaskScheduler.java         # 定时任务
```

### 步骤 2: 添加 Settings 常量 ✅
- `Settings.Global.AGENT_SERVICE_ENABLED`
- `Settings.Global.MINIMAX_API_KEY`

### 步骤 3: 注册到 SystemServer ✅

### 步骤 4: Skill 配置系统 (待实现)
- [ ] `SkillRegistry.java` - 技能注册表
- [ ] `skills.json` - 技能配置文件
- [ ] Skill 加载/保存逻辑

### 步骤 5: 语音唤醒集成 (待实现)
- [ ] 集成 VoiceInteractionService
- [ ] 唤醒词检测
- [ ] 语音指令处理

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

## 待完成
- [ ] SkillRegistry 实现
- [ ] VoiceWakeup 集成
- [ ] Settings UI 入口
- [ ] 权限声明
- [ ] 测试验证
