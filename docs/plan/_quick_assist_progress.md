任务ID	日期	状态	说明
QA00	2026-11-14	完成	迁移自 dsh-Go-java 的 assist 包已落地：8 个数据声明改为 com.dshgojava.assist 包名、字符串资源、Manifest 服务与权限、Gradle 骨架，:app:compileDebugJavaWithJavac 通过
QA00	2026-11-14	完成	数据声明与资源审计完成，新增 5 个 JUnit 数据契约测试；javac 与 JUnit 通过，Gradle 因沙箱禁止 daemon 本地套接字未完成复验，详见 QA00.md
QA01	2026-11-14	完成	平台层与三类取料完成：复制 34 个平台类（33 个仅包名、1 个可见性适配），实现 A11yBridge/ScreenSource/ClipSource/ImageSource，14 个 JUnit 通过；Gradle 因沙箱套接字未复验，详见 QA01.md
QA02	2026-11-14	完成	实现 P0 八条任务注册表、{{text}} 模板填充与前置失败判断，新增 8 个 JUnit 测试；javac 通过，22 个 JUnit 全通过，Gradle 因沙箱套接字未复验，详见 QA02.md
QA03	2026-11-14	完成	实现 OpenAI 兼容流式模型通道、SSE/JSON 解析与未配置显式失败，新增 22 个 JUnit；44 个 JUnit 全通过，Gradle 因沙箱套接字未复验，详见 QA03.md
QA04	2026-11-14	完成	实现取料→提示词→模型→状态机的一次性调度与取消，新增 13 个 JUnit；57 个 JUnit 全通过，Gradle 因沙箱套接字未复验，详见 QA04.md
QA05	2026-11-14	完成	实现 specialUse 前台服务、可拖拽 48dp 球/5 秒 32dp 淡化、四组二级菜单与权限/模型/来源灰显文案，新增 8 个 JUnit；65 个 JUnit 全通过，Gradle 因沙箱套接字未复验，详见 QA05.md
QA06	2026-11-14	完成	实现流式结果卡片、复制分享、单轮追问与仅模型重试，新增 24 个 JUnit；89 个 JUnit 全通过，Gradle 因沙箱限制未复验，详见 QA06.md
QA07	2026-11-14	完成	打通四个屏幕任务端到端并补截图悬浮层隐藏、非视觉模型过滤与 4 个 JUnit；93 个 JUnit 全通过，Gradle 受沙箱限制未复验，详见 QA07.md
QA08	2026-11-14	完成	打通剪贴板总结/润色与图片取字/解说：新增前台剪贴板桥接、图片选择/分享与私有缓存、AssistGate 前置判断，新增 10 个 JUnit；103 个 JUnit 全通过，Gradle/真机受沙箱限制未复验，详见 QA08.md
QA09	2026-11-14	完成	实现私有模型配置、首启说明/权限引导设置页与最多 200 条本地历史，接入真实 DirectApiModelChannel 与完成结果历史记录，新增 6 个 JUnit；109 个 JUnit 全通过，Gradle/真机受环境限制未复验，详见 QA09.md
QA10	2026-11-14	完成	实现 screen.actions 地址/时间/电话抽取、卡片动作按钮与导航/日程/拨号 Intent，21 个新 JUnit，130 个测试全通过；Gradle 受沙箱限制未复验，详见 QA10.md
QA11	2026-11-14	完成	实现系统语音识别按住说话、voice.transcribe 调度与无引擎/无麦克风降级，新增 10 个 JUnit；140 个测试全通过，Gradle 受沙箱限制未复验，详见 QA11.md
QA12	2026-11-14	完成	完成 QA00–QA12 文档索引与进度对账，静态编译通过、140 个 JUnit 全通过；Gradle 受沙箱套接字限制未复验，详见 QA12.md
QA12	2026-11-14	部分完成	重写 README 索引并复核设计 §5 接口、P0 8 条、Manifest 4 项权限与 123 个 Java 文件 UTF-8 无 BOM；assembleDebug 因沙箱 HOME 写限制与本地套接字限制未进入编译，构建验收未完成，详见 QA12.md
