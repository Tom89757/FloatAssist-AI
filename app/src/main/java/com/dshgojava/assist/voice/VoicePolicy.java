package com.dshgojava.assist.voice;

import java.util.Objects;

/** Pure voice availability, text normalization and recognizer error rules. */
public final class VoicePolicy {
    public enum Availability {
        READY,
        NO_ENGINE,
        NO_PERMISSION
    }

    private VoicePolicy() { }

    /** Engine availability is reported before permission so the user can fix the real blocker. */
    public static Availability availability(boolean engineAvailable, boolean permissionGranted) {
        if (!engineAvailable) return Availability.NO_ENGINE;
        if (!permissionGranted) return Availability.NO_PERMISSION;
        return Availability.READY;
    }

    /** Keeps the recognizer's wording but removes accidental surrounding whitespace. */
    public static String normalize(String text) {
        if (text == null) return "";
        String normalized = text.strip();
        return normalized.replace('\u00a0', ' ');
    }

    public static boolean usable(String text) {
        return !normalize(text).isBlank();
    }

    /**
     * Maps SpeechRecognizer's stable public error codes to actionable text. The
     * numeric constants are kept here so this policy stays JVM-testable.
     */
    public static String errorMessage(int code) {
        return switch (code) {
            case 1 -> "语音识别超时，请重试";
            case 2 -> "语音识别网络不可用，请检查网络";
            case 3 -> "麦克风录音失败，请检查是否被其他应用占用";
            case 4 -> "语音识别服务出错，请稍后重试";
            case 5 -> "语音识别已取消";
            case 6 -> "没有听到语音，请重试";
            case 7 -> "没有识别到语音，请说得清楚一些";
            case 8 -> "语音识别引擎正忙，请稍后重试";
            case 9 -> "需要麦克风权限才能按住说话";
            case 10 -> "语音识别请求过于频繁，请稍后重试";
            case 11 -> "语音识别服务连接中断，请重试";
            case 12 -> "当前语言不受语音引擎支持";
            case 13 -> "当前语言的语音包不可用";
            case 14 -> "无法检查当前设备的语音识别支持";
            default -> "语音识别失败（代码 " + Objects.toString(code, "unknown") + "）";
        };
    }
}
