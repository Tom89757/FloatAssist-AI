package com.dshgojava.assist.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoicePolicyTest {
    @Test
    void engineFailureTakesPriorityOverPermission() {
        assertEquals(VoicePolicy.Availability.NO_ENGINE,
                VoicePolicy.availability(false, false));
        assertEquals(VoicePolicy.Availability.NO_ENGINE,
                VoicePolicy.availability(false, true));
        assertEquals(VoicePolicy.Availability.NO_PERMISSION,
                VoicePolicy.availability(true, false));
        assertEquals(VoicePolicy.Availability.READY,
                VoicePolicy.availability(true, true));
    }

    @Test
    void normalizationTrimsAndKeepsRecognizedWords() {
        assertEquals("你好 世界", VoicePolicy.normalize("  你好\u00a0世界  "));
        assertEquals("", VoicePolicy.normalize(null));
        assertTrue(VoicePolicy.usable("  你好  "));
        assertFalse(VoicePolicy.usable(" \n\t "));
    }

    @Test
    void recognizerCodesBecomeActionableMessages() {
        assertEquals("没有听到语音，请重试", VoicePolicy.errorMessage(6));
        assertEquals("没有识别到语音，请说得清楚一些", VoicePolicy.errorMessage(7));
        assertEquals("需要麦克风权限才能按住说话", VoicePolicy.errorMessage(9));
        assertEquals("当前语言不受语音引擎支持", VoicePolicy.errorMessage(12));
        assertEquals("语音识别失败（代码 99）", VoicePolicy.errorMessage(99));
    }
}
