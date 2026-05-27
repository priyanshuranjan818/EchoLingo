package com.echolingo.app.ui.player

import com.echolingo.app.domain.model.Cue

fun findActiveCue(cues: List<Cue>, positionMs: Long): Cue? {
    var lo = 0
    var hi = cues.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val cue = cues[mid]
        when {
            positionMs < cue.startMs -> hi = mid - 1
            positionMs > cue.endMs -> lo = mid + 1
            else -> return cue
        }
    }
    return null
}
