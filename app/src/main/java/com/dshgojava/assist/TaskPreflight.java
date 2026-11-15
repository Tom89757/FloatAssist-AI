package com.dshgojava.assist;

import com.dshgojava.assist.source.CaptureBundle;
import java.util.Objects;

/** Pure preflight checks shared by the menu, runner and tests. */
public final class TaskPreflight {
    private TaskPreflight() { }

    public enum Failure {
        SOURCE_UNAVAILABLE,
        MODEL_UNAVAILABLE,
        VISION_UNSUPPORTED,
        EMPTY_TEXT,
        MISSING_IMAGE
    }

    public record Result(boolean allowed, Failure failure, String message) {
        public Result {
            failure = allowed ? null : Objects.requireNonNull(failure, "failure");
            message = message == null ? "" : message;
        }

        public static Result ok() {
            return new Result(true, null, "");
        }

        public static Result failed(Failure failure, String message) {
            return new Result(false, failure, message);
        }

        public boolean failed() {
            return !allowed;
        }
    }

    public static Result beforeStart(
            QuickTask task,
            boolean sourceAvailable,
            boolean modelReady,
            boolean visionSupported) {
        Objects.requireNonNull(task, "task");
        if (!sourceAvailable) {
            return Result.failed(Failure.SOURCE_UNAVAILABLE, sourceMessage(task.source()));
        }
        if (!modelReady) {
            return Result.failed(Failure.MODEL_UNAVAILABLE, "未配置模型");
        }
        if (task.needsVision() && !visionSupported) {
            return Result.failed(Failure.VISION_UNSUPPORTED, "当前模型不支持读图");
        }
        return Result.ok();
    }

    public static Result afterCapture(QuickTask task, CaptureBundle bundle) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(bundle, "bundle");
        if (!task.needsVision() && !bundle.hasText()) {
            if (task.source() == SourceKind.SCREEN && bundle.hasImages()) {
                return Result.failed(Failure.EMPTY_TEXT,
                        "当前页面没有可读文字，请改用“这是什么”");
            }
            return Result.failed(Failure.EMPTY_TEXT, "没有可处理的内容");
        }
        if (task.needsVision() && !bundle.hasImages()) {
            String message = task.source() == SourceKind.IMAGE
                    ? "尚未选择图片"
                    : "当前页面无法读取";
            return Result.failed(Failure.MISSING_IMAGE, message);
        }
        return Result.ok();
    }

    private static String sourceMessage(SourceKind source) {
        return switch (source) {
            case SCREEN -> "开启无障碍以读取屏幕";
            case WEB -> "开启无障碍以读取网页";
            case CLIP -> "剪贴板不可用";
            case IMAGE -> "尚未选择图片";
            case VOICE -> "没有可用的语音引擎";
            case NONE -> "当前任务不可用";
        };
    }
}
