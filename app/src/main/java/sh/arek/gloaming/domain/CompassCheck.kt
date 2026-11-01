package sh.arek.gloaming.domain

import kotlin.math.abs
import sh.arek.gloaming.astronomy.AltAz

/** How far the platform vouches for the compass, weakest first. */
enum class CompassAccuracy {
    Unreliable,
    Low,
    Medium,
    High;

    companion object {
        /**
         * Maps the platform's sensor status: 0 unreliable, 1 low, 2 medium,
         * 3 high (the `SENSOR_STATUS_*` constants, named here so this stays
         * free of Android). Anything else reads as [Unreliable], the safe
         * side, because the only consequence is offering a check.
         */
        fun fromSensorStatus(status: Int): CompassAccuracy = when (status) {
            1 -> Low
            2 -> Medium
            3 -> High
            else -> Unreliable
        }
    }
}

/**
 * The rules for checking the compass against the moon.
 *
 * The moon is the one target the user can point at with certainty and whose
 * true bearing the app already knows, so lining the phone up on it shows how
 * far the compass reads from true.
 */
object CompassCheck {
    const val OFFER_EVERY_MILLIS = 7 * 24 * 60 * 60 * 1000L

    /** The largest change one check may make; more is not a compass error. */
    const val MAX_OFFSET_DEGREES = 30.0

    /** How far the phone's tilt may differ from the moon's altitude. */
    const val MAX_TILT_MISMATCH = 5.0

    /** Below this the moon is too near the horizon to be lined up with. */
    const val MIN_MOON_ALTITUDE = 5.0

    /**
     * Whether to offer the check: only when the compass is [CompassAccuracy.Unreliable]
     * or [CompassAccuracy.Low], and at most once per [OFFER_EVERY_MILLIS]
     * since [offeredAt] (null if never offered).
     */
    fun shouldOffer(accuracy: CompassAccuracy, offeredAt: Long?, now: Long): Boolean {
        if (accuracy != CompassAccuracy.Unreliable && accuracy != CompassAccuracy.Low) return false
        return offeredAt == null || now - offeredAt >= OFFER_EVERY_MILLIS
    }

    /**
     * The compass offset that would put [pointing] on the [moon], or null when
     * the check cannot be trusted.
     *
     * The correction is the moon's azimuth minus the pointing azimuth, wrapped
     * to -180..180; the result is [currentOffset] plus that, normalised to the
     * same range. Null when the moon is below [MIN_MOON_ALTITUDE] or at or
     * above [Guide.ZENITH_DEGREES], where a bearing means nothing, when the
     * altitudes differ by more than [MAX_TILT_MISMATCH] (the phone is not
     * pointing at the moon), or when the correction itself - the change this
     * check would make, not the new total - exceeds [MAX_OFFSET_DEGREES].
     */
    fun offsetFromMoon(pointing: AltAz, moon: AltAz, currentOffset: Double): Double? {
        if (moon.altitude < MIN_MOON_ALTITUDE) return null
        if (moon.altitude >= Guide.ZENITH_DEGREES) return null
        if (abs(pointing.altitude - moon.altitude) > MAX_TILT_MISMATCH) return null
        val correction = signed180(moon.azimuth - pointing.azimuth)
        if (abs(correction) > MAX_OFFSET_DEGREES) return null
        return signed180(currentOffset + correction)
    }

    private fun signed180(degrees: Double): Double {
        val wrapped = wrap360(degrees)
        return if (wrapped > 180.0) wrapped - 360.0 else wrapped
    }
}
