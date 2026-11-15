# QuickAssist 实施文档索引

- 权威设计：[../quick-assist.md](../quick-assist.md)
- 任务清单：[../../plan/_quick_assist_tasks.md](../../plan/_quick_assist_tasks.md)
- 进度记录：[../../plan/_quick_assist_progress.md](../../plan/_quick_assist_progress.md)
- 索引快照：2026-11-14
- 任务完成度：任务清单中的 QA00–QA12 均有实现文档与进度记录；其中 QA12 的 Gradle 构建验收未完成，见下文“未完成与未验证项”。

> 本索引中的“完成”表示对应实现与任务文档已落地；“静态验证”表示已做源码/资源/JUnit 等非 Gradle 验证，不等同于真机验收。

## 任务索引

| 状态 | 任务 | 一句话说明 | 文档 |
| --- | --- | --- | --- |
| 完成（静态验证） | QA00 骨架与声明 | 建立本应用包名、数据记录、Manifest/字符串资源与 Gradle 骨架。 | [QA00.md](QA00.md) |
| 完成（静态验证） | QA01 平台层与取料 | 复制悬浮球与无障碍截图能力，并实现屏幕、剪贴板、图片三类取料。 | [QA01.md](QA01.md) |
| 完成（静态验证） | QA02 任务注册表 | 实现 P0 八条任务、`{{text}}` 模板填充与前置失败判断。 | [QA02.md](QA02.md) |
| 完成（静态验证） | QA03 模型通道 | 实现直连 OpenAI 兼容接口的 SSE 流式通道与未配置时的显式失败。 | [QA03.md](QA03.md) |
| 完成（静态验证） | QA04 调度器 | 实现取料 → 提示词 → 模型 → 状态机的一次性调度与取消。 | [QA04.md](QA04.md) |
| 完成（静态验证） | QA05 悬浮球 | 实现 `specialUse` 前台服务、可拖拽悬浮球与四组二级菜单。 | [QA05.md](QA05.md) |
| 完成（静态验证） | QA06 结果卡片 | 实现流式结果卡片、复制分享、关闭与一轮追问。 | [QA06.md](QA06.md) |
| 完成（静态验证） | QA07 屏幕组任务 | 打通总结、提字、翻译、这是什么四个屏幕任务及降级路径。 | [QA07.md](QA07.md) |
| 完成（静态验证） | QA08 文本与图片组 | 打通剪贴板总结/润色与图片取字/解说，并接入前置可用性判断。 | [QA08.md](QA08.md) |
| 完成（静态验证） | QA09 历史与设置 | 实现私有模型配置、首启权限引导设置页与最多 200 条本地历史。 | [QA09.md](QA09.md) |
| 完成（静态验证） | QA10 结构化动作 | 抽取地址、时间、电话，并通过动作卡片跳转导航、日程和拨号。 | [QA10.md](QA10.md) |
| 完成（静态验证） | QA11 语音组 | 实现系统语音识别按住说话、语音转写任务与无引擎降级。 | [QA11.md](QA11.md) |
| 部分完成：构建验收阻断 | QA12 收尾 | 完成文档索引、契约/P0/权限/编码自检并尝试全量构建；Gradle 因沙箱限制未通过。 | [QA12.md](QA12.md) |

## 建议阅读顺序

1. 先读 [../quick-assist.md](../quick-assist.md)，确认产品边界、接口、降级矩阵与验收标准。
2. 基础链路：[QA00.md](QA00.md) → [QA01.md](QA01.md) → [QA02.md](QA02.md) → [QA03.md](QA03.md) → [QA04.md](QA04.md)。
3. 用户可见链路：[QA05.md](QA05.md) → [QA06.md](QA06.md) → [QA07.md](QA07.md) → [QA08.md](QA08.md)。
4. 扩展能力：[QA09.md](QA09.md) → [QA10.md](QA10.md) → [QA11.md](QA11.md)。
5. 最后读 [QA12.md](QA12.md)，集中查看差异、验证限制和真机待验项。

## 本次一致性自检

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 设计 §5 公开接口 | 核心签名一致，存在已登记差异 | `QuickTask`、`SourceKind`、`OutputKind`、`CaptureBundle`、`AssistSource`、`ModelChannel`、`AssistResult`、`AssistRunner` 的必需组件/方法均存在；差异见下。 |
| `TaskRegistry` P0 | 通过，8/8 | id、source、output、needsVision 与设计 §7 的八条 P0 表一致。 |
| Manifest 权限 | 通过，4/4 齐全 | 设计要求项 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS` 均已声明；另声明 `INTERNET`、`ACCESS_NETWORK_STATE`、`RECORD_AUDIO`。 |
| Java 编码 | 通过 | 仓库源文件共 123 个 `.java`；UTF-8 校验通过，BOM 为 0，非法 UTF-8 为 0。 |
| Gradle 打包 | 未通过（环境阻断） | `./gradlew :app:assembleDebug` 未进入 Android 编译；未生成新 APK，详见 [QA12.md](QA12.md)。 |

### 接口与结构差异

- `AssistRunner.AssistRun.state()` 实际返回 `com.dshgojava.assist.StateStore<AssistResult>`；设计 §5 写的是旧包名 `com.dshgojava.app.state.StateStore<AssistResult>`。这是命名空间差异，观察者 + 不可变快照语义不变。
- `ModelChannel` 在设计的 `ready()`/`complete(...)` 之外增加了默认方法 `supportsVision()`，用于视觉能力灰显；属于向后兼容扩展。
- 设计 §10 假设复用 `PhoneA11yService` 且不新增无障碍服务；独立应用实际新增最小 `AssistA11yService`，仅桥接无障碍实例，不执行后台操控。
- 设计 §7 的 P1 中 `web.summarize`、`web.ask`、`image.qr`、`pdf.summarize` 不在 QA00–QA12 任务清单内，未实现，也不计为本批未完成任务；本批仅实现 `screen.actions` 与 `voice.transcribe`。

## 未完成与未验证项

- **QA12 构建验收未完成**：`assembleDebug` 因当前沙箱无法创建 Gradle daemon 所需本地套接字而未进入编译；这是环境阻断，不代表源码编译结论。
- **真机冒烟未运行**：悬浮窗、无障碍截图、剪贴板后台限制、系统语音识别、真实模型 SSE 与 Intent 跳转均需 Android 设备验证。
- **APK 不可作为本次验收证据**：`app/build/outputs/apk/debug/app-debug.apk` 是 2026-11-14 20:37 的早期骨架产物，本次未生成新包。
- **任务清单无缺失文档**：`docs/plan/_quick_assist_tasks.md` 的 13 个任务均有对应文档；`_quick_assist_progress.md` 中原先均记为完成，本次复核把 QA12 的构建验收状态单独标为未完成。
