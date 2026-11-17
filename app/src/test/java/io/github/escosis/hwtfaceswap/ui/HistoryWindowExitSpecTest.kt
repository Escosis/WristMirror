package io.github.escosis.hwtfaceswap.ui

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.anim.DecelerateEasing

/** Production spec and real Compose animation math; not Android rendering or wall-clock timing. */
class HistoryWindowExitSpecTest {
    private fun normalCloseSpec(): TweenSpec<Float> =
        tween(durationMillis = 260, easing = DecelerateEasing(1.5f))

    private fun value(spec: TweenSpec<Float>, millis: Long, start: Float = 0f, velocity: Float = 0f): Float =
        spec.vectorize(Float.VectorConverter).getValueFromNanos(
            millis * 1_000_000L, AnimationVector1D(start), AnimationVector1D(1f), AnimationVector1D(velocity),
        ).value

    @Test fun retainsNormalDurationAndNoDelay() {
        val actual = historyWindowExitSpec()
        assertEquals(260, actual.durationMillis)
        assertEquals(0, actual.delay)
        assertEquals(260_000_000L, actual.vectorize(Float.VectorConverter).getDurationNanos(
            AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f),
        ))
    }

    @Test fun everyMillisecondMatchesNormalClosingCurve() {
        val actual = historyWindowExitSpec()
        val expected = normalCloseSpec()
        var previous = 0f
        for (millis in 0L..300L) {
            val progress = value(actual, millis)
            assertEquals("frame $millis", value(expected, millis), progress, 0.000001f)
            assertTrue(progress >= previous)
            previous = progress
        }
    }

    @Test fun legacyLinearCurveIsSlowerEarlyButHasSameTotalDuration() {
        val actual = historyWindowExitSpec()
        val legacy = tween<Float>(durationMillis = 260, easing = LinearEasing)
        for (millis in listOf(16L, 50L, 130L)) {
            assertTrue("early frame $millis", value(actual, millis) > value(legacy, millis))
        }
        assertEquals(0f, value(actual, 0L), 0f)
        assertEquals(1f, value(actual, 260L), 0f)
        assertEquals(value(legacy, 260L), value(actual, 260L), 0f)
    }

    @Test fun interruptedStartsAndVelocitiesKeepTheSameNormalExit() {
        val actual = historyWindowExitSpec()
        val expected = normalCloseSpec()
        for (start in listOf(0f, 0.2f, 0.7f, 0.99f)) {
            for (velocity in listOf(-0.5f, 0f, 0.5f)) {
                for (millis in 0L..260L step 13) {
                    assertEquals(value(expected, millis, start, velocity), value(actual, millis, start, velocity), 0.000001f)
                }
            }
        }
    }

    @Test fun sharedCurveKeepsTheMeasuredNormalDownwardDisplacement() {
        val actual = historyWindowExitSpec()
        val expected = normalCloseSpec()
        for (geometry in listOf(
            HistoryPanelGeometry(1500f, 400f, 2200f),
            HistoryPanelGeometry(0f, 500f, 1000f),
            HistoryPanelGeometry(250f, 300f, 800f),
        )) {
            assertTrue(geometry.isPlaced)
            for (millis in 0L..260L step 13) {
                assertEquals(geometry.offset(value(expected, millis)), geometry.offset(value(actual, millis)), 0.001f)
            }
            assertTrue(geometry.isBelowBottom(value(actual, 260L)))
        }
    }
}
