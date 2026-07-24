package com.berke.ioniqscope.connection

import com.berke.ioniqscope.data.PerfRunDao
import com.berke.ioniqscope.data.PerfRunEntity
import com.berke.ioniqscope.performance.PerfState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists finished acceleration runs.
 *
 * Lives at application scope rather than in a ViewModel so that a run still gets
 * saved if the Performance screen is left (or the phone is pocketed) mid-run.
 */
class PerfRunRecorder(
    private val manager: ObdConnectionManager,
    private val dao: PerfRunDao,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun start() {
        scope.launch {
            var previousPhase = PerfState.Phase.IDLE
            manager.perfState.collect { state ->
                if (previousPhase == PerfState.Phase.RUNNING &&
                    state.phase == PerfState.Phase.DONE
                ) {
                    save(state)
                }
                previousPhase = state.phase
            }
        }
    }

    private suspend fun save(state: PerfState) {
        // A run that crossed no target at all is just pulling out of a parking
        // space — not worth a history entry.
        if (state.splits.isEmpty()) return

        dao.insert(
            PerfRunEntity(
                recordedAtEpochMs = now(),
                zeroTo50Ms = state.splits[SPLIT_50],
                zeroTo100Ms = state.splits[SPLIT_100],
                zeroTo120Ms = state.splits[SPLIT_120],
                zeroTo100mMs = state.splits[SPLIT_100M],
                zeroTo402mMs = state.splits[SPLIT_402M],
                maxKmh = state.maxKmh,
                distanceM = state.distanceM,
                durationMs = state.elapsedMs
            )
        )
    }

    companion object {
        // Must match the keys PerformanceMeter builds from its target lists.
        const val SPLIT_50 = "0-50 km/h"
        const val SPLIT_100 = "0-100 km/h"
        const val SPLIT_120 = "0-120 km/h"
        const val SPLIT_100M = "0-100 m"
        const val SPLIT_402M = "0-402 m"
    }
}
