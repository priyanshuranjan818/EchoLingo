package com.echolingo.app.ui.player

import com.echolingo.app.domain.model.Cue

/**
 * Binary-searches [cues] (sorted by startMs) to find the cue active at [positionMs].
 *
 * A small grace window of 80 ms is applied past each cue's end time. VTT tracks
 * from YouTube frequently have tiny inter-cue gaps (1–5 ms). Without the grace
 * window a 50 ms poll can fire inside that gap and return null, causing a single
 * subtitle frame to flash off for one tick. The 80 ms window is small enough that
 * it does not visually overlap with the *next* cue.
 */
private const val GRACE_MS = 80L

fun findActiveCue(cues: List<Cue>, positionMs: Long): Cue? {
    if (cues.isEmpty()) return null
    var lo = 0
    var hi = cues.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val cue = cues[mid]
        when {
            positionMs < cue.startMs        -> hi = mid - 1
            positionMs > cue.endMs + GRACE_MS -> lo = mid + 1
            else                             -> return cue
        }
    }
    return null
}
