package com.bareflash.app.console.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {

    @Test
    fun expandsTheBashStyleEscapes() {
        assertEquals("user@bf:~$ ", Prompt.format("\\u@\\h:\\w\\$ ", "user", "bf", "mtk"))
    }

    @Test
    fun dollarBecomesHashForRoot() {
        assertEquals("root@bf:~# ", Prompt.format("\\u@\\h:\\w\\$ ", "root", "bf", "mtk"))
    }

    @Test
    fun engineEscapeShowsTheCurrentMode() {
        assertEquals("qdl \u276F ", Prompt.format("\\m \u276F ", "u", "h", "qdl"))
    }

    @Test
    fun anEscapedBackslashIsOneBackslash() {
        assertEquals("a\\b", Prompt.format("a\\\\b", "u", "h", "m"))
    }

    @Test
    fun unknownEscapesAndTrailingBackslashArePrintedAsTyped() {
        assertEquals("\\q", Prompt.format("\\q", "u", "h", "m"))
        assertEquals("a\\", Prompt.format("a\\", "u", "h", "m"))
    }

    @Test
    fun newlinesBecomeSpacesSoThePromptStaysOnOneLine() {
        assertEquals("a b", Prompt.format("a\nb", "u", "h", "m"))
    }

    @Test
    fun longPromptsAreCutToTheMaximumLength() {
        val out = Prompt.format("x".repeat(Prompt.MAX_LENGTH + 40), "u", "h", "m")
        assertEquals(Prompt.MAX_LENGTH, out.length)
    }

    @Test
    fun everyPresetProducesAShortNonEmptyPromptExceptCustom() {
        for (preset in PromptPreset.values()) {
            val out = Prompt.format(preset.ps1, "user", "host", "mtk")
            assertTrue(preset.name, out.length <= Prompt.MAX_LENGTH)
            if (preset != PromptPreset.CUSTOM) assertTrue(preset.name, out.isNotEmpty())
        }
    }
}
