package io.github.escosis.hwtfaceswap.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Production danger/opacity helpers and pinned library palette values; not rendered widgets. */
class DestructiveButtonColorsTest {
    // Mirrors the pinned ButtonDefaults arguments for fixtures; actual Primary call-site is unmodified.
    private fun primaryDefaults(scheme: Colors) = ButtonColors(
        color = scheme.primary, disabledColor = scheme.disabledPrimaryButton,
        contentColor = scheme.onPrimary, disabledContentColor = scheme.disabledOnPrimaryButton,
    )

    private fun assertErrorPair(scheme: Colors) {
        val red = destructiveButtonColors(primaryDefaults(scheme), scheme)
        assertEquals(scheme.error, red.color)
        assertEquals(scheme.error, red.disabledColor)
        assertEquals(Color.White, red.contentColor)
        assertEquals(Color.White, red.disabledContentColor)
    }

    @Test fun stockLightUsesErrorAndButtonSpecificPrimarySemantics() {
        val scheme = lightColorScheme()
        assertEquals(Color(0xFFE94634), scheme.error)
        val blue = primaryDefaults(scheme)
        assertEquals(Color(0xFF3482FF), blue.color)
        assertEquals(Color(0xFFC2D9FF), blue.disabledColor)
        assertEquals(Color.White, blue.disabledContentColor)
        assertNotEquals(scheme.disabledOnPrimary, blue.disabledContentColor)
        assertErrorPair(scheme)
    }

    @Test fun stockDarkUsesErrorAndButtonSpecificPrimarySemantics() {
        val scheme = darkColorScheme()
        assertEquals(Color(0xFFF12522), scheme.error)
        val blue = primaryDefaults(scheme)
        assertEquals(Color(0xFF277AF7), blue.color)
        assertEquals(Color(0xFF253E64), blue.disabledColor)
        assertEquals(Color(0xFF677893), blue.disabledContentColor)
        assertNotEquals(scheme.disabledOnPrimary, blue.disabledContentColor)
        assertErrorPair(scheme)
    }

    @Test fun originalErrorIsNotDeepenedPremixedOrConverted() {
        val error = Color(0.8f, 0.3f, 0.2f, 0.77f, ColorSpaces.DisplayP3)
        val scheme = lightColorScheme().copy(error = error, background = Color.Green, surface = Color.Blue)
        val red = destructiveButtonColors(primaryDefaults(scheme), scheme)
        assertEquals(error, red.color)
        assertEquals(error, red.disabledColor)
        assertEquals(error.alpha, red.color.alpha, 0f)
        assertEquals(ColorSpaces.DisplayP3, red.color.colorSpace)
    }

    @Test fun onlyDisabledDangerUsesWholeLayerOpacity() {
        assertEquals(1f, actionButtonOpacity(enabled = true, destructive = false), 0f)
        assertEquals(1f, actionButtonOpacity(enabled = false, destructive = false), 0f)
        assertEquals(1f, actionButtonOpacity(enabled = true, destructive = true), 0f)
        assertEquals(0.38f, actionButtonOpacity(enabled = false, destructive = true), 0f)
    }

    @Test fun dangerForegroundRemainsWhiteBeforeTheWholeLayerFade() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val customPrimary = primaryDefaults(scheme).copy(contentColor = Color.Magenta, disabledContentColor = Color.Green)
            val red = destructiveButtonColors(customPrimary, scheme)
            assertEquals(Color.White, red.contentColor)
            assertEquals(Color.White, red.disabledContentColor)
            assertEquals(1f, red.disabledContentColor.alpha, 0f)
        }
    }

    @Test fun disabledLayerOverlayFollowsTheActualParentBackground() {
        val opacity = actionButtonOpacity(enabled = false, destructive = true)
        val light = lightColorScheme().error.copy(alpha = opacity).compositeOver(Color.White)
        val darkParent = Color(0xFF121212)
        val dark = darkColorScheme().error.copy(alpha = opacity).compositeOver(darkParent)
        assertEquals(Color(0xFFF7B9B2).toArgb(), light.toArgb())
        assertEquals(Color(0xFF671918).toArgb(), dark.toArgb())
        // Whole-layer opacity also fades white glyph pixels over the parent, not over a premixed red.
        val glyph = Color.White.copy(alpha = opacity).compositeOver(darkParent)
        assertEquals(Color(0xFF6C6C6C).toArgb(), glyph.toArgb())
        assertNotEquals(dark, darkColorScheme().error.copy(alpha = opacity).compositeOver(Color.White))
    }

    @Test fun helpersLeaveThePaletteAndPrimaryColorsUnchanged() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val blue = primaryDefaults(scheme)
            val before = blue.copy()
            val error = scheme.error
            val buttonText = scheme.disabledOnPrimaryButton
            destructiveButtonColors(blue, scheme)
            actionButtonOpacity(enabled = false, destructive = true)
            assertEquals(before, blue)
            assertEquals(error, scheme.error)
            assertEquals(buttonText, scheme.disabledOnPrimaryButton)
        }
    }
}
