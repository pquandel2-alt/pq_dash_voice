package cc.quandel.dashvoice.audio

import kotlin.math.sqrt

/**
 * Applies software microphone gain without hard-clipping spoken audio.
 *
 * The old implementation multiplied every sample and clamped it to int16. At high far-field
 * settings this turned speech peaks into flat square waves which VAD could still detect, but STT
 * could no longer decode reliably. This limiter keeps the requested gain while there is headroom,
 * attacks immediately on loud input, and releases gradually to avoid audible pumping.
 */
class PcmGainLimiter(
    private val targetPeak: Int = 28_000,
    private val releaseFactor: Float = 0.08f
) {
    data class Stats(
        val rawRms: Float,
        val outputRms: Float,
        val rawPeak: Int,
        val outputPeak: Int,
        val requestedGain: Float,
        val appliedGain: Float,
        val limited: Boolean
    )

    private var appliedGain = 1.0f

    fun process(frame: ShortArray, requestedGain: Float): Stats {
        val requested = requestedGain.coerceIn(MIN_GAIN, MAX_GAIN)
        var rawPeak = 0
        var rawSquareSum = 0.0

        for (sample in frame) {
            val value = sample.toInt()
            val magnitude = if (value == Short.MIN_VALUE.toInt()) 32_768 else kotlin.math.abs(value)
            if (magnitude > rawPeak) rawPeak = magnitude
            rawSquareSum += value.toDouble() * value.toDouble()
        }

        val peakSafeGain = if (rawPeak > 0) targetPeak.toFloat() / rawPeak else requested
        val desiredGain = minOf(requested, peakSafeGain)
        appliedGain = if (desiredGain < appliedGain) {
            desiredGain // immediate attack: never clip a newly loud frame
        } else {
            appliedGain + (desiredGain - appliedGain) * releaseFactor
        }

        var outputPeak = 0
        var outputSquareSum = 0.0
        for (i in frame.indices) {
            val output = (frame[i] * appliedGain).toInt().coerceIn(-targetPeak, targetPeak)
            frame[i] = output.toShort()
            val magnitude = kotlin.math.abs(output)
            if (magnitude > outputPeak) outputPeak = magnitude
            outputSquareSum += output.toDouble() * output.toDouble()
        }

        val count = frame.size.coerceAtLeast(1)
        return Stats(
            rawRms = (sqrt(rawSquareSum / count) / 32_768.0).toFloat(),
            outputRms = (sqrt(outputSquareSum / count) / 32_768.0).toFloat(),
            rawPeak = rawPeak,
            outputPeak = outputPeak,
            requestedGain = requested,
            appliedGain = appliedGain,
            limited = desiredGain < requested - 0.01f
        )
    }

    companion object {
        private const val MIN_GAIN = 0.25f
        private const val MAX_GAIN = 6.0f
    }
}
