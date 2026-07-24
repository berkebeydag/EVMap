package com.berke.ioniqscope.performance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Motordan gelen tek hız ölçümü. */
data class SpeedSample(val timeMs: Long, val speedKmh: Double)

/** UI'ın dinlediği canlı performans durumu. */
data class PerfState(
    val phase: Phase = Phase.IDLE,
    val currentKmh: Double = 0.0,
    val elapsedMs: Long = 0,       // kalkıştan bu yana geçen süre
    val distanceM: Double = 0.0,   // kalkıştan bu yana kat edilen mesafe
    val maxKmh: Double = 0.0,
    val splits: Map<String, Long> = emptyMap()  // "0-100 km/h" -> milisaniye
) {
    enum class Phase { IDLE, RUNNING, DONE }
}

/**
 * OBD hız örneklerinden 0-100 km/h vb. hesaplar.
 *  - Kalkışı otomatik algılar (hız eşiği geçince).
 *  - Hedef hız/mesafeyi geçtiği ANI iki örnek arasında İNTERPOLE eder
 *    (010D 1 km/h çözünürlükte olduğu için bu, hassasiyeti belirgin artırır).
 *  - Durunca yeni run'a otomatik hazırlanır.
 */
class PerformanceMeter(
    private val speedTargetsKmh: List<Int> = listOf(50, 100, 120),
    private val distanceTargetsM: List<Int> = listOf(100, 402), // 402m ≈ çeyrek mil
    private val launchThresholdKmh: Double = 0.5,  // bunu geçince "kalkış"
    private val resetThresholdKmh: Double = 0.3    // buna inince "durdu"
) {
    private val _state = MutableStateFlow(PerfState())
    val state: StateFlow<PerfState> = _state.asStateFlow()

    private var launchTimeMs = 0L
    private var prev: SpeedSample? = null
    private var distanceM = 0.0
    private var maxKmh = 0.0
    private val splits = linkedMapOf<String, Long>()
    private val hitSpeed = mutableSetOf<Int>()
    private val hitDist = mutableSetOf<Int>()

    /** ObdEngine her hız örneğinde bunu çağırır. */
    fun onSpeed(s: SpeedSample) {
        val p = prev
        when (_state.value.phase) {
            PerfState.Phase.IDLE, PerfState.Phase.DONE -> {
                // Dururken tekrar hareket başlarsa yeni ölçüm başlat
                if (s.speedKmh > launchThresholdKmh && (p == null || p.speedKmh <= launchThresholdKmh)) {
                    startRun(s)
                } else if (s.speedKmh <= resetThresholdKmh && _state.value.phase == PerfState.Phase.DONE) {
                    _state.value = PerfState() // durdu → IDLE'a dön, yeni run'a hazır
                }
            }
            PerfState.Phase.RUNNING -> accumulate(p, s)
        }
        prev = s
    }

    private fun startRun(s: SpeedSample) {
        launchTimeMs = s.timeMs
        distanceM = 0.0; maxKmh = s.speedKmh
        splits.clear(); hitSpeed.clear(); hitDist.clear()
        _state.value = PerfState(phase = PerfState.Phase.RUNNING, currentKmh = s.speedKmh)
    }

    private fun accumulate(prev: SpeedSample?, cur: SpeedSample) {
        if (prev == null) return
        val dtMs = cur.timeMs - prev.timeMs
        if (dtMs <= 0) return

        // Mesafe: trapez integrali (km/h → m/s için ÷3.6)
        val vAvgMs = (prev.speedKmh + cur.speedKmh) / 2.0 / 3.6
        distanceM += vAvgMs * (dtMs / 1000.0)
        maxKmh = maxOf(maxKmh, cur.speedKmh)
        val elapsed = cur.timeMs - launchTimeMs

        // Hız hedefleri — geçiş anını interpole et
        for (t in speedTargetsKmh) {
            if (t !in hitSpeed && prev.speedKmh < t && cur.speedKmh >= t) {
                val frac = (t - prev.speedKmh) / (cur.speedKmh - prev.speedKmh)
                val crossMs = prev.timeMs + (frac * dtMs).toLong()
                splits["0-$t km/h"] = crossMs - launchTimeMs
                hitSpeed.add(t)
            }
        }
        // Mesafe hedefleri
        for (d in distanceTargetsM) {
            if (d !in hitDist && distanceM >= d) {
                splits["0-$d m"] = elapsed
                hitDist.add(d)
            }
        }

        val allDone = hitSpeed.size == speedTargetsKmh.size && hitDist.size == distanceTargetsM.size
        val stopped = cur.speedKmh <= resetThresholdKmh
        _state.value = _state.value.copy(
            phase = if (allDone || stopped) PerfState.Phase.DONE else PerfState.Phase.RUNNING,
            currentKmh = cur.speedKmh,
            elapsedMs = elapsed,
            distanceM = distanceM,
            maxKmh = maxKmh,
            splits = LinkedHashMap(splits)
        )
    }

    fun reset() { prev = null; _state.value = PerfState() }
}
