# dsh-Go-assist 实施任务规范（Codex 自动任务）

把 `docs/design/quick-assist.md` 的落地设计实现为可运行的 Android 应用。本文件是**每个实施会话的必读规范**，由 `run_quick_assist.sh` 驱动。本文件与脚本为规范文件，**不得修改**。

## 1. 目标

在本仓库（`dsh-Go-assist`，独立的单模块 Android 应用）实现悬浮球快捷 AI（QuickAssist）。新增代码全部位于 `app/src/main/java/com/dshgojava/assist`。

## 2. 仓库角色与参考范围

| 仓库 | 角色 | 读写 |
| --- | --- | --- |
| 本仓库 `dsh-Go-assist` | **唯一可写区**，应用本体 | 可读写 |
| `../dsh-Go-java` | **参考与复制来源**：悬浮窗、无障碍截图、UI 树、模型调用等成熟实现 | **只读** |
| `../dsh-Go` | 产品交互参考（Dart 原版） | **只读** |

**要求**：
1. 设计文档 `docs/design/quick-assist.md` 是接口与结构的**唯一权威**。
2. 需要复用某能力时，先读 `../dsh-Go-java` 中该类的**真实签名**，再复制进 `assist/platform/` 并改包名；**禁止凭记忆写接口**。
3. 设计文档与 `../dsh-Go-java` 真实代码冲突时，以真实代码为准，并在任务文档中记录差异。
4. 按需 grep / 精确读取，禁止遍历大目录。

## 3. 技术栈（不得偏离）

Java 17；Android 原生 View（XML + Java）；Gradle 8.9 + AGP 8.2.2；`namespace` 与 `applicationId` 均为 `com.dshgojava.assist`；`minSdk 26`、`targetSdk 34`；OkHttp 4.12；Gson 2.10.1；JUnit 5。

**禁止**：Kotlin、Compose、新增第三方 UI 库、在 Gradle 中通过 `project(...)` 依赖 `../dsh-Go-java` 的模块（跨仓库 project 依赖不可用）。

## 4. 交付物（每个任务四项齐全）

1. **实现代码**：`app/src/main/java/com/dshgojava/assist/**` + 必要的资源与 Manifest 改动。
2. **测试**：纯逻辑（任务注册表、模板填充、降级判断、动作解析、SSE 解析、历史存储）必须有 JUnit 5 测试；涉及 Android 框架的写"需真机验证"。
3. **任务文档**：`docs/design/quick-assist/<任务ID>.md`，含实现清单、与设计文档的差异、复用映射（复制了哪些类、改了哪些包名）、验证记录。
4. **进度行**：`docs/plan/_quick_assist_progress.md` 末尾追加（**必须最后一步**）。

## 5. 硬规则

1. **`../dsh-Go-java`、`../dsh-Go` 只读**：不写、不删、不改。
2. **规范文件不可改**：不改本文件、`run_quick_assist.sh`、`docs/design/quick-assist.md`。
3. **复制要显式**：从 `../dsh-Go-java` 复制的每个类必须在任务文档登记「源路径 → 目标路径 → 包名改动」。复制后若发现缺陷，**同时修两处**并记录。
4. **不静默降级**：无无障碍、无悬浮窗权限、未配置模型、模型不支持视觉等，必须灰显 + 明确文案，禁止假装工作。
5. **凭据与隐私**：不记录/回显/持久化 API key（只存本机私有存储）；截图与历史默认仅本地；日志脱敏。
6. **线程**：截图、UI 树抓取、网络、文件 IO 禁止在主线程。
7. **编码**：所有 `.java` 文件必须 **UTF-8 无 BOM**（本项目已因 PowerShell BOM 导致编译失败过一次）。
8. **Git**：不做任何 git 写操作（不 add/commit/push）；进度用进度文件表达。
9. **验证诚实**：只写实际执行的命令与结果；未运行写"未运行"+ 原因。

## 6. 命令

```bash
./gradlew :app:compileDebugJavaWithJavac   # 每个任务必做
./gradlew :app:testDebugUnitTest           # 有测试时做
./gradlew :app:assembleDebug               # 收尾
```

Windows 下需设置 `JAVA_HOME` 为 JDK 17（`gradle.properties` 已固定 `org.gradle.java.home`）。环境缺 SDK 时：静态自检（导入一致性、签名对齐）+ 文档标注"未验证编译"。

## 7. 任务清单

| ID | 任务 | 主要产出 |
| --- | --- | --- |
| QA00 | 骨架与声明 | 数据声明、Manifest、字符串资源、Gradle 可编译（**已由迁移完成，见进度文件**） |
| QA01 | 平台层与取料 | 从 `../dsh-Go-java` 复制 `OverlayBall`/`CaptureVisibility`/`ScreenShooter`/`TreeCapture` 到 `assist/platform`；实现 `AssistSource`/`CaptureBundle`/`ScreenSource`/`ClipSource`/`ImageSource`/`A11yBridge` |
| QA02 | 任务注册表 | `TaskRegistry`（P0 8 条）+ 模板填充 + 前置失败判断 + JUnit |
| QA03 | 模型通道 | `PromptImage`/`ModelChannel`/`DirectApiModelChannel`（OkHttp + SSE 流式）/`NullModelChannel` + SSE 解析测试 |
| QA04 | 调度器 | `AssistRunner`/`DefaultAssistRunner`（取料→拼提示→模型→状态机）+ JUnit |
| QA05 | 悬浮球 | `AssistOverlayService`（前台服务）+ `AssistBall` + `AssistMenuView` |
| QA06 | 结果卡片 | `AssistCardView`/`AssistCardLayer`/`AssistAskBar`（流式、复制、分享、追问 1 轮） |
| QA07 | 屏幕组任务 | `screen.summarize`/`screen.extract`/`screen.translate`/`screen.what` 端到端 |
| QA08 | 文本与图片组 | `clip.summarize`/`clip.polish`/`image.ocr`/`image.explain` |
| QA09 | 历史与设置 | `AssistHistoryStore`/`AssistPrefs` + 设置页（API key、开关、权限引导） |
| QA10 | 结构化动作 | 地址/时间/电话抽取 + `ACTION_SHEET` + Intent 跳转 + JUnit |
| QA11 | 语音组 | `VoiceSource`（录音 → 转写）+ 降级 |
| QA12 | 收尾 | 全量编译/测试、`docs/design/quick-assist/README.md` 索引、进度对账 |

## 8. 每个任务的执行模板

1. 读本文件 + `docs/design/quick-assist.md` 中该任务相关章节；
2. 若要复用，读 `../dsh-Go-java` 中真实类的完整源码（禁止凭记忆）；
3. 写代码 + 测试（UTF-8 无 BOM）；
4. 运行编译命令并修复；
5. 写 `docs/design/quick-assist/<ID>.md`；
6. 最后追加进度行。
