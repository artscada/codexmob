# 👨‍💻 开源共创指南

欢迎加入 Operit 开源生态！我们欢迎不同类型的贡献者。

## 脚本与插件开发者

### 📜 脚本开发

Operit 支持通过 TypeScript/JavaScript 脚本来扩展 AI 的能力。完整指南请参考 [脚本开发指南 (SCRIPT_DEV_GUIDE.md)](./SCRIPT_DEV_GUIDE.md)。

### 🔌 MCP 插件开发

你可以开发自己的 MCP 插件来扩展 AI 的能力，如网页浏览、图像处理等。在 Operit AI 中导入你的插件仓库或 zip 文件即可开始。

## Operit 本体开发者

参与 Operit AI 本体开发，请遵循以下精简指南。

### 🛠️ 环境搭建

在开始开发之前，请参考 [完整编译指南 (BUILDING.md)](./BUILDING.md) 搭建 Android 开发环境。

### 🚀 开发前必读

1.  **先沟通**: 在 [Issue 区](https://github.com/AAswordman/Operit/issues) 提出你的想法或认领任务，**避免重复造轮子**。
2.  **研究代码**: 动手前，请**深入阅读**相关模块的现有代码，理解项目的设计模式和架构。
3.  **保持兼容**: 新功能必须**向前兼容**，不能破坏现有用户体验或数据结构。
4.  **遵循结构**: 将新文件放置在项目结构中合适的目录，保持代码库整洁。

### 🎨 代码风格

我们的代码风格...比较随性。欢迎你来帮忙统一！

- commit 信息非常"创意丰富"
- 注释语言混搭风，中英文随心切换
- 代码风格多元化

### 🔄 提交流程

为了顺利合入你的代码，请严格遵循以下流程：

1.  **准备工作**:
    - Fork 本仓库并 Clone 到本地。
    - 添加上游仓库: `git remote add upstream https://github.com/AAswordman/Operit.git`

2.  **开始开发**:
    - 同步最新的 `main` 和 `pr-branch` 分支。
      ```bash
      git fetch upstream
      git checkout main
      git merge upstream/main
      git checkout pr-branch
      git merge main
      ```
    - 从 `pr-branch` 创建你的功能分支。
      ```bash
      git checkout -b feature/your-feature-name
      ```

3.  **提交代码**:
    - 完成开发后，**同步 `main` 分支的最新代码**。推荐使用 `rebase` 以保持历史记录清晰。
      ```bash
      git fetch upstream
      git rebase upstream/main # 或者 git merge upstream/main
      ```
    - 解决所有冲突后，推送到你的远程分支。
      ```bash
      # 如果 rebase 过，需要使用 --force
      git push origin feature/your-feature-name --force
      ```

4.  **创建 Pull Request**:
    - 打开 GitHub，创建一个 Pull Request，**目标分支请选择 `pr-branch`**。

### ⚠️ 重要提醒

- **先沟通，再开发**，避免重复工作。
- **所有 PR 必须提交到 `pr-branch` 分支**。
- **提交 PR 前，请务必同步最新的 `main` 分支**，并解决所有冲突。
- 在 PR 中清晰说明你的改动。

### ✅ 必过的状态检查（Required Status Check）

`main` 分支启用了分支保护，目前有两项检查被设为**必过状态检查（required status check）**：
**`Web Chat First-Screen Budget`** 和 **`Android APK Build`**。

**`Web Chat First-Screen Budget`** 由 `.github/workflows/web-chat-first-screen.yml` 提供：

- 只有当 PR 改动了 `web-chat/**`（或该工作流文件）时才会真正执行构建并跑
  `npm --prefix web-chat run measure:check`，对比首屏 gzip JS 体积基线；其它 PR 会直接
  通过这项检查（不会因为路径过滤而被永久卡住）。
- 当首屏体积相对基线增长超过阈值时该检查会失败，从而**阻止 PR 合入 `main`**，确保首屏
  体积回归不会进入主分支。
- 如果某次增长是预期内的，请按下文「刷新基线」的步骤更新并提交基线文件，让检查重新通过。

**`Android APK Build`** 由 `.github/workflows/build-apk.yml` 提供：

- 该工作流既在 PR 上运行，也在 push 到 `main` 时运行。这样**在合入前**就能发现破坏 Android
  构建的改动，而不是等代码进了 `main` 才暴露。
- 由于 Android 构建很重（需要 JDK + Android SDK + NDK），它沿用了和首屏检查相同的
  git-diff 路径过滤：只有当 PR 改动了与 Android 构建相关的路径（如 `app/**`、各原生模块、
  Gradle 配置或该工作流文件本身）时才会真正执行完整构建；其它 PR 会直接通过这项检查
  （不会因为路径过滤而被永久卡住，也不会让无关 PR 付出完整构建的代价）。
- 当 `./gradlew assembleDebug` 失败时该检查会失败，从而**阻止 PR 合入 `main`**，确保编译失败
  不会进入主分支。

> 说明：分支保护未对管理员强制生效（`enforce_admins=false`），以便仓库维护者在紧急情况下
> 仍可合入，同时不影响平台向 `main` 的直接同步。

---

我们期待您的贡献！你的每一次 PR、Issue 和讨论都在帮助 Operit 成长。
> **关于项目维护**: 项目的发展依赖社区的参与。感谢你的每一份贡献！

---

## 社区贡献与衍生项目指南

我们非常欢迎并鼓励社区基于 Operit AI 进行二次创作和改进。为了维护项目的透明度和社区的健康发展，我们强烈建议所有衍生项目：

1.  **在知名的、公开的代码托管平台（如 GitHub, GitLab, Gitee 等）上发布您的源代码。** 我们建议使用这些大型平台，而不是自行搭建难以访问的小型代码站点，因为这能确保社区可以方便地审查、学习和贡献代码，从而真正实现“开源”的价值。
2.  **在您的项目文档中明确致谢并链接回本项目。** 这有助于用户追溯代码来源，也是对我们工作的尊重和认可。

遵循这些建议将帮助我们共同构建一个更加开放、协作和安全的社区环境。 