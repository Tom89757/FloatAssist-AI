package com.dshgojava.assist.platform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Budgets and validates borrowed-frame OCR; unknown or failed output never becomes evidence. */
public final class LocalOcrBoundary {
    private static final int MAX_LINES = 256;
    private static final int MAX_TEXT_BYTES = 16 * 1024;

    private LocalOcrBoundary() { }

    @FunctionalInterface
    public interface Recognizer {
        LocalOcrResult recognize() throws InterruptedException;
    }

    public static LocalOcrResult read(boolean requested, boolean privacyAllowed,
                                      int width, int height, long deadlineUptimeMs,
                                      LongSupplier clock, BooleanSupplier cancelled,
                                      Recognizer recognize) {
        if (!requested) return new LocalOcrResult.Unavailable(LocalOcrResult.Reason.NOT_REQUESTED);
        if (!privacyAllowed) {
            return new LocalOcrResult.Unavailable(LocalOcrResult.Reason.PRIVACY_UNVERIFIED);
        }
        if (cancelled.getAsBoolean()) {
            return new LocalOcrResult.Unavailable(LocalOcrResult.Reason.CANCELLED);
        }
        if (clock.getAsLong() >= deadlineUptimeMs) {
            return new LocalOcrResult.Unavailable(LocalOcrResult.Reason.DEADLINE);
        }
        LocalOcrResult result;
        try {
            result = recognize.recognize();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new LocalOcrResult.Unavailable(LocalOcrResult.Reason.CANCELLED);
        } catch (Exception error) {
            return new LocalOcrResult.Failed(LocalOcrResult.Failure.ENGINE);
        }
        if (cancelled.getAsBoolean()) {
            return new LocalOcrResult.Unavailable(LocalOcrResult.Reason.CANCELLED);
        }
        if (clock.getAsLong() >= deadlineUptimeMs) {
            return new LocalOcrResult.Unavailable(LocalOcrResult.Reason.DEADLINE);
        }
        if (!(result instanceof LocalOcrResult.Recognized recognized)) return result;
        if (recognized.lines().size() > MAX_LINES) {
            return new LocalOcrResult.Failed(LocalOcrResult.Failure.RESULT_LIMIT);
        }
        int bytes = 0;
        List<LocalOcrResult.Line> lines = new ArrayList<>(recognized.lines().size());
        for (LocalOcrResult.Line line : recognized.lines()) {
            if (line.left() < 0 || line.top() < 0 || line.right() <= line.left()
                    || line.bottom() <= line.top() || line.right() > width || line.bottom() > height
                    || line.confidence() != null && (!Float.isFinite(line.confidence())
                    || line.confidence() < 0f || line.confidence() > 1f)) {
                return new LocalOcrResult.Failed(LocalOcrResult.Failure.INVALID_RESULT);
            }
            if (line.text() == null || line.text().length() > MAX_TEXT_BYTES) {
                return new LocalOcrResult.Failed(LocalOcrResult.Failure.RESULT_LIMIT);
            }
            bytes += line.text().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_TEXT_BYTES) {
                return new LocalOcrResult.Failed(LocalOcrResult.Failure.RESULT_LIMIT);
            }
            lines.add(line);
        }
        return new LocalOcrResult.Recognized(lines);
    }

    public static String message(LocalOcrResult result) {
        if (result instanceof LocalOcrResult.Recognized) {
            return "本机识别结果仅留在本机，未提供视觉元素；不能据此猜坐标，请 handoff。";
        }
        if (result instanceof LocalOcrResult.Failed) {
            return "本机文字识别失败或结果不完整，未提供视觉元素；请 handoff。";
        }
        LocalOcrResult.Unavailable unavailable = (LocalOcrResult.Unavailable) result;
        return switch (unavailable.reason()) {
            case NOT_INSTALLED -> "本机文字识别后端尚未安装，未提供视觉元素；不能据此猜坐标，请 handoff。";
            case PRIVACY_UNVERIFIED -> "无法确认本屏的本机识别隐私边界，未识别文字；请 handoff。";
            default -> "本次本机文字识别不可用，未提供视觉元素；请 handoff。";
        };
    }
}
