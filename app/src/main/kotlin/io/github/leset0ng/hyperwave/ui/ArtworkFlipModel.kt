package io.github.leset0ng.hyperwave.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** The pending image, rather than spring activity, determines whether updates merge. */
internal class ArtworkFlipModel<T : Any> {
    enum class Direction(val halfTurn: Double) { FORWARD(-180.0), BACKWARD(180.0) }

    var displayed: T? = null
        private set
    var pending: T? = null
        private set
    private var identity: Any? = null
    var angle = 0.0
        private set
    var targetAngle = 0.0
        private set
    var velocity = 0.0
        private set
    private var face = 0
    private var lastTime = 0.0
    private var revealTime = Double.NEGATIVE_INFINITY
    private var removalTime: Double? = null
    private var opacityStart = 0.0
    private var opacityTarget = 0.0
    private var opacityTime = 0.0
    private var ratioStart = 1.0
    private var ratioTarget = 1.0
    private var ratioTime = 0.0
    private var ratioDuration = 0.0

    val renderedAngle: Double get() = angle - roundFace(angle) * 180.0
    var opacity = 0.0
        private set
    var aspectRatio = 1.0
        private set
    var reveal = 0.0
        private set

    fun update(image: T?, seed: Any?, ratio: Double, now: Double,
               direction: Direction = Direction.FORWARD, animate: Boolean = true) {
        advance(now)
        if (!animate) {
            displayed = image
            pending = null
            identity = if (image == null) null else seed
            angle = 0.0
            targetAngle = 0.0
            velocity = 0.0
            face = 0
            revealTime = Double.NEGATIVE_INFINITY
            reveal = 0.0
            removalTime = null
            opacity = if (image == null) 0.0 else 1.0
            opacityStart = opacity
            opacityTarget = opacity
            opacityTime = now
            aspectRatio = if (image == null) 1.0 else validRatio(ratio)
            ratioStart = aspectRatio
            ratioTarget = aspectRatio
            ratioDuration = 0.0
            return
        }
        if (image == null) {
            if (identity == null && pending == null && (removalTime != null || displayed == null)) return
            identity = null
            pending = null
            // Keep the last image only for its removal fade; stale completions are guarded.
            removalTime = now
            fadeTo(0.0, now)
            return
        }
        val first = identity == null
        val same = !first && identity == seed
        identity = seed
        removalTime = null
        if (first) {
            settleRotation()
            displayed = image
            pending = null
            fadeTo(1.0, now)
            resize(ratio, now, 0.250)
        } else if (pending != null) {
            pending = image
            resize(ratio, now, 0.300)
        } else if (same) {
            displayed = image
            aspectRatio = validRatio(ratio)
            ratioStart = aspectRatio
            ratioTarget = aspectRatio
            ratioDuration = 0.0
        } else {
            pending = image
            targetAngle += direction.halfTurn
            resize(ratio, now, 0.300)
        }
    }

    /** Time is in seconds, adjusted for the platform animator duration scale by the renderer. */
    fun advance(now: Double) {
        val dt = maxOf(0.0, now - lastTime)
        lastTime = now
        if (angle != targetAngle || velocity != 0.0) {
            // Exact underdamped oscillator: mass 1.25, k 300, c 25, speed 0.5.
            val displacement = angle - targetAngle
            val decay = exp(-DECAY * dt)
            val phase = FREQUENCY * dt
            val a = displacement
            val b = (velocity + DECAY * displacement) / FREQUENCY
            val position = a * cos(phase) + b * sin(phase)
            angle = targetAngle + decay * position
            velocity = decay * (-DECAY * position +
                    FREQUENCY * (-a * sin(phase) + b * cos(phase)))
            val targetFace = roundFace(targetAngle)
            val threshold = (face + targetFace) * 90.0
            val crossed = targetFace > face && angle >= threshold - 9.0 ||
                    targetFace < face && angle <= threshold + 9.0
            if (pending != null && crossed) {
                displayed = pending
                pending = null
                face = targetFace
                revealTime = now
            }
            // Android's stop tolerance is local policy, not an iOS timing claim.
            if (abs(angle - targetAngle) < 0.01 && abs(velocity) < 0.01) {
                angle = targetAngle
                velocity = 0.0
            }
        }
        opacity = opacityStart + (opacityTarget - opacityStart) *
                ease((now - opacityTime) / 0.250, false)
        val removedAt = removalTime
        if (removedAt != null && now >= removedAt + 0.250) {
            displayed = null
            removalTime = null
            settleRotation()
            resize(1.0, removedAt + 0.250, 0.250)
        }
        aspectRatio = ratioStart + (ratioTarget - ratioStart) *
                if (ratioDuration == 0.0) 1.0 else ease((now - ratioTime) / ratioDuration, false)
        reveal = 1.0 - ease((now - revealTime) / 0.150, true)
    }

    fun animating(now: Double): Boolean = angle != targetAngle || velocity != 0.0 ||
            pending != null || removalTime != null ||
            now < opacityTime + 0.250 && opacityStart != opacityTarget ||
            now < ratioTime + ratioDuration || now < revealTime + 0.150

    private fun settleRotation() {
        angle = 0.0
        targetAngle = 0.0
        velocity = 0.0
        face = 0
        revealTime = Double.NEGATIVE_INFINITY
        reveal = 0.0
    }

    private fun fadeTo(value: Double, now: Double) {
        opacityStart = opacity
        opacityTarget = value
        opacityTime = now
    }

    private fun resize(ratio: Double, now: Double, duration: Double) {
        ratioStart = aspectRatio
        ratioTarget = validRatio(ratio)
        ratioTime = now
        ratioDuration = duration
    }

    companion object {
        private const val DECAY = 5.0
        private val FREQUENCY = sqrt(60.0 - DECAY * DECAY)
        private fun validRatio(ratio: Double): Double =
                if (ratio.isFinite() && ratio > 0.0) ratio else 1.0
        internal fun roundFace(degrees: Double): Int =
                (sign(degrees) * floor(abs(degrees / 180.0) + 0.5)).toInt()

        /** CSS/SwiftUI standard ease-in-out (0.42,0,0.58,1) and ease-out (0,0,0.58,1). */
        private fun ease(fraction: Double, out: Boolean): Double {
            if (fraction <= 0.0) return 0.0
            if (fraction >= 1.0) return 1.0
            var low = 0.0
            var high = 1.0
            repeat(18) {
                val t = (low + high) / 2.0
                val x = 3.0 * (1 - t) * (1 - t) * t * (if (out) 0.0 else 0.42) +
                        3.0 * (1 - t) * t * t * 0.58 + t * t * t
                if (x < fraction) low = t else high = t
            }
            val t = (low + high) / 2.0
            return 3.0 * (1 - t) * t * t + t * t * t
        }
    }
}
