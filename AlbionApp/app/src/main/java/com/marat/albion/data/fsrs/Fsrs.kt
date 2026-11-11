package com.marat.albion.data.fsrs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** Оценка ответа, как в Anki / FSRS. */
enum class Rating(val value: Int, val label: String) {
    Again(1, "Снова"), Hard(2, "Трудно"), Good(3, "Хорошо"), Easy(4, "Легко");

    companion object {
        fun of(value: Int) = entries.first { it.value == value }
    }
}

/** Состояние карточки. NEW хранится как отсутствие строки в БД. */
object CardState {
    const val NEW = 0
    const val LEARNING = 1
    const val REVIEW = 2
    const val RELEARNING = 3
}

/** Память карточки — всё, что нужно планировщику. */
data class Memory(
    val state: Int = CardState.NEW,
    val step: Int = 0,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0,
    val lastReview: Long = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
)

/**
 * Реализация FSRS-6 (Free Spaced Repetition Scheduler), повторяет логику
 * эталонной py-fsrs 6.x: кратковременные шаги изучения, затем интервалы в днях,
 * рассчитанные из стабильности памяти и желаемой вероятности вспомнить.
 */
class Fsrs(
    val desiredRetention: Double = 0.9,
    private val w: DoubleArray = DEFAULT_W,
    private val learningSteps: List<Long> = listOf(1 * MINUTE, 10 * MINUTE),
    private val relearningSteps: List<Long> = listOf(10 * MINUTE),
    private val maximumInterval: Int = 36500,
    private val enableFuzz: Boolean = true,
    private val random: Random = Random.Default,
    /**
     * Номер учебного дня по моменту времени. Если задан, для карточек в REVIEW прошедшее время
     * считается в учебных днях, как в Anki: интервалы привязаны к началу дня, и ответ вечером
     * с повтором следующим утром — это целый день, а не 12 часов. Учебные шаги — по часам.
     */
    private val studyDay: ((Long) -> Long)? = null,
) {
    private val decay = -w[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    fun retrievability(m: Memory, now: Long): Double {
        if (m.state == CardState.NEW || m.stability <= 0) return 0.0
        val elapsedDays = max(0.0, (now - m.lastReview).toDouble() / DAY)
        return (1.0 + factor * elapsedDays / m.stability).pow(decay)
    }

    /** Вероятность вспомнить через [elapsedDays] дней при стабильности [stability]. */
    fun recallProbability(elapsedDays: Double, stability: Double): Double =
        (1.0 + factor * max(0.0, elapsedDays) / stability).pow(decay)

    /**
     * Новые стабильность и сложность после ответа — ядро модели памяти FSRS-6.
     * Используется и планировщиком, и оптимизатором параметров.
     */
    fun memoryStep(stability: Double, difficulty: Double, isNew: Boolean, elapsedDays: Double, rating: Rating): Pair<Double, Double> {
        val g = rating.value
        val s: Double
        val d: Double
        if (isNew) {
            s = initialStability(g)
            d = initialDifficulty(g)
        } else if (elapsedDays < 1.0) {
            s = shortTermStability(stability, g)
            d = nextDifficulty(difficulty, g)
        } else {
            val r = recallProbability(elapsedDays, stability)
            s = if (rating == Rating.Again) nextForgetStability(difficulty, stability, r) else nextRecallStability(difficulty, stability, r, g)
            d = nextDifficulty(difficulty, g)
        }
        return clampStability(s) to d
    }

    /** Предпросмотр всех четырёх вариантов — для подписей на кнопках. */
    fun preview(m: Memory, now: Long): Map<Rating, Memory> =
        Rating.entries.associateWith { review(m, it, now, fuzz = false) }

    fun review(m: Memory, rating: Rating, now: Long, fuzz: Boolean = enableFuzz): Memory {
        val g = rating.value
        val elapsedDays = elapsedDays(m, now)

        val (stability, difficulty) = memoryStep(
            stability = m.stability,
            difficulty = m.difficulty,
            isNew = m.state == CardState.NEW || m.stability <= 0,
            elapsedDays = elapsedDays,
            rating = rating,
        )

        var state = m.state
        var step = m.step
        val interval: Long // мс
        var lapses = m.lapses

        when (m.state) {
            CardState.NEW, CardState.LEARNING -> {
                if (m.state == CardState.NEW) step = 0
                val steps = learningSteps
                if (steps.isEmpty() || (step >= steps.size && g >= 2)) {
                    state = CardState.REVIEW; step = 0
                    interval = days(nextIntervalDays(stability, fuzz))
                } else when (rating) {
                    Rating.Again -> { state = CardState.LEARNING; step = 0; interval = steps[0] }
                    Rating.Hard -> {
                        state = CardState.LEARNING
                        interval = when {
                            step == 0 && steps.size == 1 -> (steps[0] * 1.5).toLong()
                            step == 0 -> (steps[0] + steps[1]) / 2
                            else -> steps[min(step, steps.lastIndex)]
                        }
                    }
                    Rating.Good -> {
                        if (step + 1 >= steps.size) {
                            state = CardState.REVIEW; step = 0
                            interval = days(nextIntervalDays(stability, fuzz))
                        } else {
                            state = CardState.LEARNING; step += 1
                            interval = steps[step]
                        }
                    }
                    Rating.Easy -> {
                        state = CardState.REVIEW; step = 0
                        interval = days(nextIntervalDays(stability, fuzz))
                    }
                }
            }

            CardState.REVIEW -> {
                if (rating == Rating.Again) {
                    lapses += 1
                    if (relearningSteps.isEmpty()) {
                        interval = days(nextIntervalDays(stability, fuzz))
                    } else {
                        state = CardState.RELEARNING; step = 0
                        interval = relearningSteps[0]
                    }
                } else {
                    interval = days(nextIntervalDays(stability, fuzz))
                }
            }

            else -> { // RELEARNING
                val steps = relearningSteps
                if (steps.isEmpty() || (step >= steps.size && g >= 2)) {
                    state = CardState.REVIEW; step = 0
                    interval = days(nextIntervalDays(stability, fuzz))
                } else when (rating) {
                    Rating.Again -> { step = 0; interval = steps[0] }
                    Rating.Hard -> interval = when {
                        step == 0 && steps.size == 1 -> (steps[0] * 1.5).toLong()
                        step == 0 -> (steps[0] + steps[1]) / 2
                        else -> steps[min(step, steps.lastIndex)]
                    }
                    Rating.Good -> {
                        if (step + 1 >= steps.size) {
                            state = CardState.REVIEW; step = 0
                            interval = days(nextIntervalDays(stability, fuzz))
                        } else {
                            step += 1; interval = steps[step]
                        }
                    }
                    Rating.Easy -> {
                        state = CardState.REVIEW; step = 0
                        interval = days(nextIntervalDays(stability, fuzz))
                    }
                }
            }
        }

        return Memory(
            state = state,
            step = step,
            stability = stability,
            difficulty = difficulty,
            due = now + interval,
            lastReview = now,
            reps = m.reps + 1,
            lapses = lapses,
        )
    }

    /** Сколько дней прошло с прошлого ответа: для REVIEW — в учебных днях (если задан [studyDay]). */
    fun elapsedDays(m: Memory, now: Long): Double = when {
        m.lastReview <= 0 -> 0.0
        m.state == CardState.REVIEW && studyDay != null -> max(0L, studyDay(now) - studyDay(m.lastReview)).toDouble()
        else -> (now - m.lastReview).toDouble() / DAY
    }

    // ---- формулы FSRS-6 ----

    private fun initialStability(g: Int) = max(w[g - 1], MIN_STABILITY)

    private fun initialDifficulty(g: Int, clamp: Boolean = true): Double {
        val d = w[4] - exp(w[5] * (g - 1)) + 1.0
        return if (clamp) d.coerceIn(1.0, 10.0) else d
    }

    private fun nextDifficulty(d: Double, g: Int): Double {
        val delta = -w[6] * (g - 3)
        val damped = d + delta * (10.0 - d) / 9.0
        val reverted = w[7] * initialDifficulty(4, clamp = false) + (1 - w[7]) * damped
        return reverted.coerceIn(1.0, 10.0)
    }

    private fun shortTermStability(s: Double, g: Int): Double {
        var inc = exp(w[17] * (g - 3 + w[18])) * s.pow(-w[19])
        if (g >= 3) inc = max(inc, 1.0)
        return s * inc
    }

    private fun nextRecallStability(d: Double, s: Double, r: Double, g: Int): Double {
        val hardPenalty = if (g == 2) w[15] else 1.0
        val easyBonus = if (g == 4) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp((1 - r) * w[10]) - 1) * hardPenalty * easyBonus)
    }

    private fun nextForgetStability(d: Double, s: Double, r: Double): Double {
        val longTerm = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp((1 - r) * w[14])
        val shortTerm = s / exp(w[17] * w[18])
        return min(longTerm, shortTerm)
    }

    private fun clampStability(s: Double) = max(s, MIN_STABILITY)

    fun nextIntervalDays(stability: Double, fuzz: Boolean = false): Int {
        val raw = stability / factor * (desiredRetention.pow(1.0 / decay) - 1.0)
        var ivl = raw.roundToInt().coerceIn(1, maximumInterval)
        if (fuzz && ivl >= 3) ivl = fuzzed(ivl)
        return ivl
    }

    private fun fuzzed(ivl: Int): Int {
        var delta = 1.0
        for ((start, end, f) in FUZZ_RANGES) {
            delta += f * max(min(ivl.toDouble(), end) - start, 0.0)
        }
        val minIvl = max(2, (ivl - delta).roundToInt())
        val maxIvl = min((ivl + delta).roundToInt(), maximumInterval)
        return if (maxIvl <= minIvl) minIvl else random.nextInt(minIvl, maxIvl + 1)
    }

    private fun days(n: Int): Long = n * DAY

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        private const val MIN_STABILITY = 0.001

        /** Параметры FSRS-6 по умолчанию (py-fsrs 6). */
        val DEFAULT_W = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )

        /** Допустимые границы каждого параметра FSRS-6 (как в эталонном оптимизаторе). */
        val BOUNDS: List<ClosedFloatingPointRange<Double>> = listOf(
            0.001..100.0, 0.001..100.0, 0.001..100.0, 0.001..100.0,
            1.0..10.0, 0.001..4.0, 0.001..4.0, 0.001..0.75, 0.0..4.5, 0.0..0.8, 0.001..3.5,
            0.001..5.0, 0.001..0.25, 0.001..0.9, 0.0..4.0, 0.0..1.0, 1.0..6.0,
            0.0..2.0, 0.0..2.0, 0.0..0.8, 0.1..0.8,
        )

        fun encodeParams(w: DoubleArray): String = w.joinToString(",") { "%.4f".format(java.util.Locale.US, it) }

        fun decodeParams(s: String?): DoubleArray? {
            val values = s?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
            // NaN или бесконечность в параметрах сломали бы сроки всех карточек.
            return if (values.size == DEFAULT_W.size && values.all { it.isFinite() }) values.toDoubleArray() else null
        }

        private val FUZZ_RANGES = listOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.1),
            Triple(20.0, Double.MAX_VALUE, 0.05),
        )

        /** «10 мин», «3 дн», «2 мес», «1.5 г» — компактно для кнопок. */
        fun formatInterval(ms: Long): String {
            val minutes = ms / MINUTE.toDouble()
            return when {
                minutes < 1 -> "<1 мин"
                minutes < 60 -> "${minutes.roundToInt()} мин"
                minutes < 60 * 24 -> "${(minutes / 60).roundToInt()} ч"
                else -> {
                    val d = minutes / (60 * 24)
                    when {
                        d < 30 -> "${d.roundToInt()} дн"
                        d < 365 -> trim(d / 30.4) + " мес"
                        else -> trim(d / 365) + " г"
                    }
                }
            }
        }

        private fun trim(v: Double): String {
            val r = (v * 10).roundToInt() / 10.0
            return if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
        }
    }
}
