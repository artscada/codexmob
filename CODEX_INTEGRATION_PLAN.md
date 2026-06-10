# Codex Android 深度整合方案

> 基于 Operit 原生 Compose UI 二次开发，用原生 Codex CLI 替换 AI 引擎
> 保留 Operit 全部基础设施（Linux 环境、Shizuku、MCP、工具系统）

---

## 一、项目概述

### 目标
将 **Codex CLI**（OpenAI 开源 AI 编码代理）深度整合到 Android 平台，以 **Operit 项目为底座**，保留其完整的 Android 集成能力（Shizuku、Linux 环境、系统工具、MCP 框架），替换其 AI 提供者为 **原生 Codex Agent 引擎**。

### 核心理念
- **Operit（底座）** → Android 集成层（UI 框架、Shizuku、Linux 环境、系统工具）
- **Codex CLI（引擎）** → AI Agent 能力（Agent 循环、MCP、Skills、Sandbox、工具调用）
- **CodexAgentProvider（桥梁）** → 实现 Operit 的 AIService 接口，连接 Codex exec-server

### 架构图

```
┌──────────────────────────────────────────────────────────┐
│              用户界面层 (Operit Compose UI)               │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │  MainActivity                                     │  │
│  │  ┌──────────────────────────────────────────────┐ │  │
│  │  │  底部导航栏 (BottomNavBar)                     │ │  │
│  │  │  [💬 AI 聊天] [🔧 工具] [📦 扩展] [⚙️ 设置]  │ │  │
│  │  └──────────────────────────────────────────────┘ │  │
│  │                                                    │  │
│  │  ┌─ AIChatScreen (聊天界面) ────────────────────┐  │  │
│  │  │  ■ 复用 Operit 全部已有聊天组件               │  │  │
│  │  │    - 消息气泡、Markdown 渲染                   │  │  │
│  │  │    - 输入框、附件上传                           │  │  │
│  │  │    - 聊天历史、搜索                             │  │  │
│  │  │  ■ 新增 Codex 模式:                           │  │  │
│  │  │    - 模式切换开关 (Operit AI ↔ Codex AI)       │  │  │
│  │  │    - Agent 状态指示器                           │  │  │
│  │  │    - 工具调用实时可视化                          │  │  │
│  │  │    - MCP 使用记录                              │  │  │
│  │  └──────────────────────────────────────────────┘  │  │
│  │                                                    │  │
│  │  ┌─ 设置页面 (Operit 设置扩展) ─────────────────┐  │  │
│  │  │  ■ 在现有设置中新增 "Codex" 分区              │  │  │
│  │  │    - 连接方式 (本地/API/自定义)                │  │  │
│  │  │    - Codex 配置                                │  │  │
│  │  │    - MCP 服务器管理                            │  │  │
│  │  │    - Skills 管理                               │  │  │
│  │  │    - 二进制管理                                │  │  │
│  │  └──────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
├──────────────────────────────────────────────────────────┤
│              桥接层 (Codex-Operit Bridge)                 │
│                                                          │
│  CodexAgentProvider (实现 AIService 接口)                 │
│  ├─ sendMessage → WebSocket → codex exec-server          │
│  ├─ 流式响应逐块传入 ChatViewModel                       │
│  ├─ 支持工具调用、MCP、Skills                            │
│  └─ Token 计数、连接测试                                │
│                                                          │
│  CodexMCPBridge (Operit 工具 → Codex MCP)               │
│  ├─ Android 文件系统 MCP 服务器                          │
│  ├─ Shizuku 特权操作 MCP 服务器                          │
│  ├─ SMS/电话/位置工具 MCP 服务器                         │
│  └─ 截图/无障碍工具 MCP 服务器                           │
│                                                          │
├──────────────────────────────────────────────────────────┤
│              运行时层 (Android Services)                  │
│                                                          │
│  CodexRuntimeService (前台服务)                           │
│  ├─ 管理 Codex CLI 进程生命周期                          │
│  ├─ 二进制下载、验证、提取                                │
│  ├─ 启动 exec-server (WebSocket 模式)                    │
│  ├─ 进程健康监控与自动重启                               │
│  └─ 状态广播给 UI 层                                    │
│                                                          │
├──────────────────────────────────────────────────────────┤
│              基础设施层 (Operit 保留全部)                 │
│                                                          │
│  Linux 环境 (proot)  │  Shizuku  │  MCP 系统  │  工具系统 │
│  文件系统  │  网络  │  权限管理  │  通知服务  │  数据库   │
└──────────────────────────────────────────────────────────┘
```

---

## 二、Codex 原生 Skills 系统

### 概述
Codex 使用 **插件市场 (Plugin Marketplace)** 机制管理 skills。每个 skill 是一个包含 `SKILL.md` 和脚本的目录，通过 Git 仓库分发。

### CLI 命令
```bash
# 管理市场源
codex plugin marketplace add <git-url|local-path>  # 添加市场
codex plugin marketplace list                       # 列出市场
codex plugin marketplace upgrade                    # 刷新市场
codex plugin marketplace remove <name>              # 移除市场

# 管理插件
codex plugin list          # 列出可用插件
codex plugin add <name>    # 安装插件
codex plugin remove <name> # 卸载插件
```

### Android 集成

#### 通信方式
所有命令通过 `codex exec-server` 的 WebSocket JSON-RPC 接口执行：
```json
// 请求: 列出插件
{"jsonrpc":"2.0","id":"1","method":"exec","params":{"command":"codex plugin list"}}

// 请求: 安装插件
{"jsonrpc":"2.0","id":"2","method":"exec","params":{"command":"codex plugin add codex-sub-agents"}}

// 响应
{"jsonrpc":"2.0","id":"1","result":{"stdout":"...","stderr":"","exit_code":0}}
```

#### UI 界面
- **CodexSkillsScreen.kt** (Compose, Material 3)
  - 顶部：市场选择器（下拉列表）
  - 中部：插件列表（卡片式布局）
    - 每个卡片显示：名称、描述、版本、安装状态
    - 操作按钮：安装/卸载/配置
  - 底部：添加市场按钮
- **集成到 Operit 导航**：新增 `NavItem.CodexSkills`，在底部导航"扩展"页面中

#### 预设 Skills
| Skill 名称 | 来源 | 功能 |
|-----------|------|------|
| codex-sub-agents | 官方 | 子代理系统 |
| imagegen | 官方 | 图像生成 |
| openai-docs | 官方 | OpenAI 文档查询 |
| plugin-creator | 官方 | 插件创建器 |
| skill-creator | 官方 | Skill 创建器 |
| search-codex-chats | 官方 | 聊天历史搜索 |
| composio-cli | 社区 | 工具集成 |
| flightclaw | 社区 | 航班查询 |
| telegram-bridge-send | 社区 | Telegram 消息 |

---

## 三、Codex 原生 MCP 系统

### 概述
Codex 的 MCP (Model Context Protocol) 系统允许 Agent 调用外部工具和服务。支持两种服务器类型：

### 支持类型

#### 1. HTTP 流式 MCP 服务器
```bash
codex mcp add github --url https://api.github.com/mcp
```
用于远程服务，支持 Bearer Token 认证。

#### 2. Stdio 本地 MCP 服务器
```bash
codex mcp add filesystem -- npx @modelcontextprotocol/server-filesystem /path
```
用于本地工具，通过 stdio 通信。

### Android 集成

#### MCP 管理界面
- **CodexMCPScreen.kt** (Compose, Material 3)
  - MCP 服务器列表（开关式卡片）
    - 名称、类型、状态、URL/命令
    - 启用/禁用开关
    - 编辑/删除按钮
  - 添加 MCP 服务器对话框
    - 名称输入
    - 类型选择 (HTTP/Stdio)
    - URL 或命令输入
    - 环境变量配置
  - 认证管理 (login/logout)

#### 预置 Android MCP 工具包
在 `CodexMCPBridge.kt` 中实现，作为本地 stdio MCP 服务器自动注册：

```kotlin
class AndroidMCPServer : MCPServer {
    // 文件系统操作
    @Tool("android_read_file") 
    fun readFile(path: String): String
    
    @Tool("android_write_file")
    fun writeFile(path: String, content: String): Boolean
    
    // Shizuku 特权操作
    @Tool("shizuku_exec")
    fun shizukuExec(command: String): String
    
    // 系统工具
    @Tool("android_screenshot")
    fun takeScreenshot(): String  // base64 png
    
    @Tool("android_get_location")
    fun getLocation(): Location
    
    @Tool("android_send_sms")
    fun sendSms(number: String, message: String): Boolean
    
    @Tool("android_make_call")
    fun makeCall(number: String): Boolean
}
```

#### Operit ↔ Codex MCP 双向桥接

```
Operit MCP 服务器 (已有)                 Codex MCP 服务器 (原生)
        │                                       │
        └──────────┬───────────────────────────┘
                   │
          CodexMCPBridge.kt
                   │
        ┌──────────┴──────────┐
        │                     │
  Operit MCP 注册表     Codex exec-server
        │                     │
   Operit 工具系统       Codex Agent 循环
```

---

## 四、UI 设计方案

### 4.1 导航结构

```
MainActivity
├── 启动流程 (Operit 原有)
│   ├── 协议同意 → 权限引导 → 插件加载 → 主界面
│
├── 底部导航栏 (新增"扩展"标签)
│   ├── 💬 AI 聊天 (AIChatScreen)
│   ├── 🔧 工具   (工具页面, Operit 原有)
│   ├── 📦 扩展   (新增: MCP + Skills 管理)
│   └── ⚙️ 设置   (Operit 设置 + Codex 分区)
│
├── AIChatScreen (聊天界面)
│   ├── 聊天头部
│   │   ├── AI 模式切换 (Operit AI ↔ Codex AI)
│   │   ├── Codex 连接状态指示器
│   │   └── Agent 运行状态
│   ├── 消息列表 (复用 Operit 已有组件)
│   │   ├── 用户消息气泡
│   │   ├── AI 消息气泡 (Codex 流式响应)
│   │   ├── 工具调用卡片 (Codex 模式特有)
│   │   │   ├── 工具名称 + 参数
│   │   │   ├── 执行状态 (等待中/执行中/完成/失败)
│   │   │   └── 执行结果预览
│   │   └── 系统消息 (连接状态、错误等)
│   ├── 输入区域 (复用 Operit 已有组件)
│   │   ├── 文本输入框
│   │   ├── 附件按钮
│   │   └── 发送按钮
│   └── 快捷操作面板
│       ├── 建议提示标签
│       └── 常用命令
│
├── 设置页面 (Operit 设置 + 新增 Codex 分区)
│   ├── Codex 设置 (新增)
│   │   ├── 连接方式
│   │   │   ├── 本地 Codex CLI (推荐)
│   │   │   ├── OpenAI 兼容 API
│   │   │   └── 自定义 WebSocket
│   │   ├── API 配置
│   │   │   ├── API Key (密码输入)
│   │   │   ├── API 端点 URL
│   │   │   └── 模型选择
│   │   ├── MCP 服务器管理 → CodexMCPScreen
│   │   ├── Skills 管理 → CodexSkillsScreen
│   │   └── 二进制管理
│   │       ├── 当前版本显示
│   │       ├── 下载进度
│   │       ├── 检查更新
│   │       └── 重新下载
│   └── (保留 Operit 原有所有设置项)
│
└── 扩展页面 (新增)
    ├── MCP 管理 (CodexMCPScreen)
    └── Skills 市场 (CodexSkillsScreen)
```

### 4.2 聊天界面 Codex 模式状态流转

```
用户发送消息
    │
    ▼
┌─────────────────────┐
│  Codex 思考中...     │  ← Agent 状态指示器
│  ● 正在分析任务...   │
└─────────────────────┘
    │
    ▼  (Codex Agent 决定调用工具)
┌─────────────────────┐
│ 📁 创建文件          │  ← 工具调用卡片
│ 路径: /workspace/... │
│ 内容: ...           │
│ ⏳ 执行中...        │
└─────────────────────┘
    │
    ▼  (工具执行完成)
┌─────────────────────┐
│ ✅ 文件已创建        │  ← 工具结果
│ 大小: 1.2KB        │
└─────────────────────┘
    │
    ▼  (Codex 继续生成响应)
┌─────────────────────┐
│ 已完成！以下是       │  ← AI 流式响应
│ 创建的 Python 计算器 │
│ ...                 │
└─────────────────────┘
    │
    ▼  (完成)
Agent 状态恢复为"就绪"
```

### 4.3 视觉设计原则
- **完全使用 Operit 现有主题** (`OperitTheme`)
- **Material 3 设计语言**
- **深色模式优先**（与 Codex 桌面端一致）
- **代码块使用等宽字体**，语法高亮
- **工具调用卡片使用不同颜色区分状态**
- **移动端适配**：大屏多列、小屏单列

---

## 五、关键技术实现

### 5.1 CodexAgentProvider (核心桥梁)

```kotlin
class CodexAgentProvider(
    private val wsUrl: String = "ws://127.0.0.1:9877"
) : AIService {

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        onTokensUpdated: suspend (Int, Int, Int) -> Unit,
        onNonFatalError: suspend (String) -> Unit
    ): Stream<String> {
        // 1. 将 chatHistory 转换为 Codex prompt
        // 2. 通过 WebSocket 发送到 exec-server
        // 3. 接收流式响应
        // 4. 工具调用通过 MCP 桥接转发到 Operit 工具系统
        // 5. 返回 Stream<String> 给 ChatViewModel
    }

    override suspend fun testConnection(context: Context): Result<String> {
        // 通过 WebSocket ping 检测连接状态
    }

    override fun cancelStreaming() {
        // 发送取消信号
    }
}
```

### 5.2 WebSocket JSON-RPC 协议

```json
// 发送 prompt
→ {"jsonrpc":"2.0","id":"1","method":"execute","params":{"prompt":"写一个计算器","stream":true}}

// 流式响应块
← {"jsonrpc":"2.0","id":"1","method":"response","params":{"content":"我将创建..."}}

// 工具调用
← {"jsonrpc":"2.0","id":"1","method":"tool_call","params":{"name":"create_file","arguments":{"path":"calc.py","content":"..."}}}

// 工具结果
→ {"jsonrpc":"2.0","id":"1","method":"tool_result","params":{"name":"create_file","result":"文件已创建"}}

// 完整响应
← {"jsonrpc":"2.0","id":"1","result":{"output":"已完成！","exit_code":0}}
```

### 5.3 CodexRuntimeService 生命周期

```
APP 启动
    │
    ▼
检查 Codex 二进制是否存在
    │
    ├──不存在──▶ 下载二进制 (显示进度)
    │               │
    │               ▼
    │            解压验证
    │               │
    └──存在────────▶ 验证完整性
                        │
                        ▼
                  启动 exec-server
                  监听 ws://127.0.0.1:9877
                        │
                        ▼
                  广播就绪状态
                        │
                        ▼
                  UI 层连接 WebSocket
                  开始 Codex 模式
```

### 5.4 二进制管理策略

| 策略 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| 首次启动下载 | 从 GitHub Releases 下载 | APK 体积小 | 需要网络 |
| 内置压缩包 | APK 内包含压缩的二进制 | 离线可用 | APK ~60MB |
| **混合策略(推荐)** | 先尝试内置，失败则下载 | 兼顾两者 | 实现略复杂 |

---

## 六、文件清单

### 新增文件

| 文件路径 | 说明 | 状态 |
|---------|------|------|
| `service/CodexRuntimeService.kt` | Codex 进程管理前台服务 | ✅ 已创建 |
| `codex/CodexManager.kt` | 二进制下载/验证/提取 | ✅ 已创建 |
| `bridge/CodexBridge.kt` | WebSocket JSON-RPC 通信 | ✅ 已创建 |
| `provider/CodexAgentProvider.kt` | AIService 接口实现 | 📝 待创建 |
| `provider/CodexMCPBridge.kt` | Operit 工具→Codex MCP 桥接 | 📝 待创建 |
| `ui/settings/CodexSettingsScreen.kt` | Codex 设置界面 | 📝 待创建 |
| `ui/mcp/CodexMCPScreen.kt` | MCP 管理界面 | 📝 待创建 |
| `ui/skills/CodexSkillsScreen.kt` | Skills 管理界面 | 📝 待创建 |

### 修改文件

| 文件路径 | 修改内容 | 状态 |
|---------|---------|------|
| `ui/main/MainActivity.kt` | 添加 Codex 导航入口 | 📝 待修改 |
| `ui/common/NavItem.kt` | 添加 Codex 导航项 | 📝 待修改 |
| `ui/features/chat/screens/AIChatScreen.kt` | 添加 Codex 模式切换 | 📝 待修改 |
| `ui/features/chat/viewmodel/ChatViewModel.kt` | 添加 CodexAgentService | 📝 待修改 |
| `AndroidManifest.xml` | 注册 CodexRuntimeService | ✅ 已修改 |
| `core/application/OperitApplication.kt` | 初始化 Codex 系统 | 📝 待修改 |

---

## 七、实施步骤

### Phase 1: 基础运行时 ✅ (已完成)
- [x] `CodexRuntimeService.kt` — 前台服务
- [x] `CodexManager.kt` — 二进制管理
- [x] `CodexBridge.kt` — WebSocket 通信
- [x] `AndroidManifest.xml` — 组件注册
- [x] 修复 Manifest XML 结构错误

### Phase 2: Codex AI 引擎集成
- [ ] `CodexAgentProvider.kt` — 实现 AIService 接口
- [ ] 修改 `ChatViewModel.kt` — 添加 CodexAgentService
- [ ] 修改 `AIChatScreen.kt` — 添加模式切换
- [ ] 修改 `NavItem.kt` — 添加 Codex 导航项
- [ ] 修改 `OperitApplication.kt` — 初始化 Codex

### Phase 3: MCP 桥接
- [ ] `CodexMCPBridge.kt` — Android 工具 MCP 服务器
- [ ] 预置 Android MCP 工具 (文件/Shizuku/SMS/位置/截图)
- [ ] Operit ↔ Codex MCP 双向通信

### Phase 4: 设置与配置 UI
- [ ] `CodexSettingsScreen.kt` — 连接方式、API Key、模型
- [ ] `CodexMCPScreen.kt` — MCP 服务器列表管理
- [ ] `CodexSkillsScreen.kt` — Skills 市场浏览

### Phase 5: 构建与发布
- [ ] GitHub Actions 工作流优化
- [ ] Codex 二进制自动下载
- [ ] APK 构建与签名
- [ ] Release 管理

---

## 八、构建与测试

### 构建命令
```bash
./gradlew assembleDebug
```

### GitHub Actions
- 每次 push 到 main 分支自动构建
- 构建产物：`app/build/outputs/apk/debug/*.apk`
- 下载地址：Actions 页面 → Artifacts

### 测试要点
1. **无网络环境**：显示引导界面，提示配置 API Key
2. **首次启动**：下载 Codex 二进制，显示进度
3. **Codex 模式**：发送 prompt，观察流式响应
4. **工具调用**：观察工具卡片显示和状态更新
5. **MCP 管理**：添加/删除 MCP 服务器
6. **Skills 管理**：浏览市场、安装/卸载
7. **崩溃恢复**：Service 崩溃后自动重启
8. **内存管理**：大模型响应时内存使用

---

## 九、注意事项

1. **Codex 二进制大小** ~180MB，首次启动需下载
2. **exec-server 模式**当前为实验性功能，配置 `experimental_features=true`
3. **Sandbox 在 Android 上不可用**，需配置 `sandbox=off`
4. **Git 集成**在 Android 上受限，配置 `skip-git-repo-check=true`
5. **API Key 安全存储**使用 Android Keystore 系统
6. **前台服务通知**展示 Codex 运行状态
7. **WebSocket 重连**网络切换时自动恢复连接

---

## 十、附录

### 相关链接
- Codex CLI 官方文档：https://github.com/openai/codex
- Operit 项目：https://github.com/AAswordman/Operit
- 本项目仓库：https://github.com/weige369/codex-android
- Codex ARM64 二进制：https://github.com/openai/codex/releases

### Codex 配置参考
```toml
# ~/.codex/config.toml
model = "gpt-4o"
provider = "openai"
approval = "never"
sandbox = "off"
skip-git-repo-check = true
experimental_features = true

[mcp]
# MCP 服务器配置

[plugins]
# 插件配置
```

### 关键技术栈
- **语言**: Kotlin 2.2.0
- **UI**: Jetpack Compose + Material 3
- **构建**: Gradle 8.13 + AGP 8.13.2
- **最小 SDK**: Android 8.1 (API 27)
- **目标 SDK**: Android 15 (API 35)
- **数据库**: ObjectBox + Room
- **特权**: Shizuku API
- **网络**: OkHttp + WebSocket
- **序列化**: kotlinx.serialization
