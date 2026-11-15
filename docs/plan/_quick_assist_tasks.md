QA00|骨架与声明|数据声明、Manifest、字符串资源与 Gradle 可编译骨架|QuickTask SourceKind OutputKind AssistRequest AssistResult AssistAction Stage
QA01|平台层与取料|从参考仓库复制悬浮球与无障碍截图能力到 assist/platform，并实现屏幕、剪贴板、图片三类取料|AssistSource CaptureBundle ScreenSource ClipSource ImageSource A11yBridge
QA02|任务注册表|实现 P0 八条任务声明、提示词模板填充与前置失败判断|TaskRegistry 模板填充 JUnit
QA03|模型通道|实现直连 OpenAI 兼容接口的流式通道与未配置时的显式失败|PromptImage ModelChannel DirectApiModelChannel NullModelChannel SSE JUnit
QA04|调度器|实现取料到模型到状态机的一次性调度与取消|AssistRunner DefaultAssistRunner JUnit
QA05|悬浮球|实现前台服务、悬浮球与二级菜单|AssistOverlayService AssistBall AssistMenuView
QA06|结果卡片|实现流式结果卡片、复制分享与一轮追问|AssistCardView AssistCardLayer AssistAskBar
QA07|屏幕组任务|打通总结、提字、翻译、这是什么四个屏幕任务|ScreenSource 接入 端到端
QA08|文本与图片组|打通剪贴板总结润色与图片取字解说|ClipSource ImageSource 接入
QA09|历史与设置|实现本地历史与设置页、模型配置与权限引导|AssistHistoryStore AssistPrefs 设置页
QA10|结构化动作|实现地址时间电话抽取与动作按钮跳转|ACTION_SHEET Intent 跳转 JUnit
QA11|语音组|实现按住说话转写与无引擎降级|VoiceSource 录音转写
QA12|收尾|全量编译测试、文档索引与进度对账|README 对账
