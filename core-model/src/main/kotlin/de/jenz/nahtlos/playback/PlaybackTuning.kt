package de.jenz.nahtlos.playback

object PlaybackTuning {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f
    const val SPEED_STEP = 0.1f
    const val DEFAULT_SPEED = 1.0f
    const val DEFAULT_SKIP_BACK_SEC = 10
    const val DEFAULT_SKIP_FORWARD_SEC = 30

    fun clampSpeed(speed: Float): Float {
        val steps = Math.round(speed / SPEED_STEP)
        return (steps * SPEED_STEP).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun nudgeSpeed(speed: Float, direction: Int): Float = clampSpeed(speed + direction * SPEED_STEP)

    fun clampSkipSeconds(seconds: Int): Int = seconds.coerceIn(5, 180)

    /** Resume at the intro boundary when playback is still at the start. */
    fun introStartMs(positionMs: Long, introSec: Int): Long? {
        if (introSec <= 0 || positionMs > 2_000L) return null
        return introSec * 1_000L
    }

    fun outroReached(positionMs: Long, durationMs: Long, outroSec: Int): Boolean {
        if (outroSec <= 0 || durationMs <= 0) return false
        val tail = outroSec * 1_000L
        return positionMs >= durationMs - tail && positionMs < durationMs - 400L
    }
}
