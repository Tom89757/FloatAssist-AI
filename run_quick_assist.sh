#!/usr/bin/env bash
# dsh-Go-assist 实施驱动脚本
#
# 按 docs/design/quick-assist.md 的落地设计，驱动 Codex 逐任务实现悬浮球快捷 AI。
# 任务规范见同目录 AGENTS.md（每个会话先读它）。
#
# 用法（WSL 内执行；需已安装并登录 codex CLI）：
#   bash run_quick_assist.sh              # 全量 QA00→QA12；已完成自动跳过
#   bash run_quick_assist.sh QA05         # 只做指定任务
#   nohup bash run_quick_assist.sh > .codex_logs/qa_driver.log 2>&1 &   # 后台
#
# 本脚本与 AGENTS.md 为规范文件，实施会话不得修改。

set -euo pipefail

ROOT="/mnt/d/Desktop/DSH/dsh-Go-assist"
REF="/mnt/d/Desktop/DSH/dsh-Go-java"
PLAN="$ROOT/docs/plan"
DOCS="$ROOT/docs/design/quick-assist"
LOGS="$ROOT/.codex_logs"
TASKS="$PLAN/_quick_assist_tasks.md"
PROGRESS="$PLAN/_quick_assist_progress.md"

mkdir -p "$PLAN" "$DOCS" "$LOGS"
cd "$ROOT"

[[ -f "$ROOT/docs/design/quick-assist.md" ]] || {
  echo "[error] 缺少设计文档 docs/design/quick-assist.md"; exit 1; }
[[ -d "$REF" ]] || {
  echo "[error] 参考仓库不存在：$REF"; exit 1; }

ONLY="${1:-}"

run_codex() {
  local prompt="$1" log="$2" attempt
  for attempt in 1 2 3; do
    if codex exec --sandbox workspace-write --skip-git-repo-check "$prompt" </dev/null >"$LOGS/$log" 2>&1; then
      return 0
    fi
    echo "[warn] codex 第 ${attempt} 次异常退出（详见 $LOGS/$log），20 秒后重试"
    sleep 20
  done
  return 1
}

# ---------- 阶段0：预置任务清单 + 进度文件（幂等） ----------
# 字段：任务ID|任务名|一句话范围|关键产出
if [[ ! -f "$TASKS" ]]; then
  cat > "$TASKS" <<'EOF'
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
EOF
  echo "阶段0：已预置任务清单 $TASKS"
fi

if [[ ! -f "$PROGRESS" ]]; then
  printf '任务ID\t日期\t状态\t说明\n' > "$PROGRESS"
fi

# ---------- 阶段1：逐任务实现（幂等，断点续跑） ----------
task_done() {
  local t="$1"
  grep -Eq "^${t}[[:space:]]" "$PROGRESS" 2>/dev/null \
    && [[ -s "$DOCS/$t.md" ]]
}

run_task() {
  local task="$1" line="$2"
  echo "实施任务：$task"
  if ! run_codex "阅读 AGENTS.md 与 docs/design/quick-assist.md 后执行任务「$task」（清单行：$line，字段为 任务ID|任务名|一句话范围|关键产出）。步骤：1) 读设计文档中与该任务相关的全部章节，接口签名与包结构以其为准；2) 需要复用能力时，读 $REF 中对应类的真实源码（app/src/main/java 下 pet、phone/a11y、state 等，按需 grep，禁止凭记忆或遍历大目录），复制进 app/src/main/java/com/dshgojava/assist/platform 并改包名为 com.dshgojava.assist.platform，在文档里登记源路径到目标路径的映射；3) 在本仓库 app/src/main/java/com/dshgojava/assist 下实现代码，遵守 AGENTS.md 第5节硬规则（参考仓库只读、不静默降级、IO与网络离开主线程、禁止 Kotlin/Compose、Java 文件必须 UTF-8 无 BOM）；4) 纯逻辑部分补 JUnit 5 测试；5) 运行 ./gradlew :app:compileDebugJavaWithJavac 并修复编译错误，有测试则运行 :app:testDebugUnitTest，如实记录结果；6) 撰写 docs/design/quick-assist/$task.md（实现清单、与设计文档的差异、复用映射、验证记录）；7) 四项交付物齐全后，最后一步在 docs/plan/_quick_assist_progress.md 末尾追加一行「$task\t$(date +%F)\t完成\t一句话说明」。" "qa_${task}.log"; then
    echo "[error] $task 重试后仍失败，跳过并继续（重跑脚本可续做）"
  fi
}

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "${line//[[:space:]]/}" ]] && continue
  [[ "$line" == \#* ]] && continue
  task="$(printf '%s' "$line" | cut -d'|' -f1 | tr -d '[:space:]')"
  [[ -z "$task" ]] && continue
  if [[ -n "$ONLY" && "$task" != "$ONLY" ]]; then continue; fi
  if task_done "$task"; then echo "跳过（已完成）：$task"; continue; fi
  run_task "$task" "$line"
done < <(tr -d '\r' < "$TASKS")

# ---------- 阶段2：收尾 ----------
if [[ -z "$ONLY" ]]; then
  echo "阶段2：收尾"
  run_codex "阅读 AGENTS.md 后执行 QuickAssist 收尾：1) 对照 docs/plan/_quick_assist_tasks.md 与 _quick_assist_progress.md，把未完成任务如实记入 docs/design/quick-assist/README.md 的完成状态；2) 重写该 README 为实施文档索引（每任务一句话 + 相对链接 + 建议阅读顺序）；3) 做一次一致性自检：确认 assist 包内公开接口与 docs/design/quick-assist.md 第5节签名一致、TaskRegistry 的 P0 条目数与设计一致、Manifest 权限齐全、所有 .java 文件为 UTF-8 无 BOM，差异如实记录；4) 运行 ./gradlew :app:assembleDebug 并如实记录结果。" "qa_stage2.log" \
    || echo "[warn] 阶段2 未完全成功（详见 $LOGS/qa_stage2.log），可重跑续做"
fi

done_count=$(grep -Ec '^QA[0-9]' "$PROGRESS" 2>/dev/null || true)
echo "执行完毕。已完成任务数：${done_count:-0}；进度见 docs/plan/_quick_assist_progress.md；日志目录：$LOGS"
