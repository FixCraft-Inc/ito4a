package com.fixcraft.ito.call

/** Remote capture samples anchor once to local monotonic arrival. This detects
 * later stalls without asserting the first packet's unknown one-way delay.
 */
class PlayoutClock {
    private var firstSample = -1L
    private var firstAt = 0L
    private var previous = -1L

    @Synchronized fun deadlineBase(sample: Long, nowMs: Long): Long? {
        require(sample >= 0 && sample % 960 == 0L && sample > previous)
        previous = sample
        if (firstSample < 0) { firstSample = sample; firstAt = nowMs }
        val expected = firstAt + (sample - firstSample) / 48
        return expected.takeIf { nowMs - it <= 120 && it - nowMs <= 120 }
    }
}
