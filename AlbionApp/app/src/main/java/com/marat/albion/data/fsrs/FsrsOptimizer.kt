package com.marat.albion.data.fsrs

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Один ответ из журнала. [stateBefore] — состояние карточки до ответа; NEW посреди истории
 * означает, что карточку сбросили («Сделать новой», сброс колоды) и её память начинается заново.
 */
data class ReviewEvent(val cardId: String, val rating: Int, val time: Long, val stateBefore: Int = -1)

sealed interface OptimizeOutcome {
    /** Недостаточно повторений с интервалом от суток. */
    data class NotEnoughData(val have: Int, val need: Int) : OptimizeOutcome

    /** Повторений достаточно, но их даты не позволяют отделить обучение от проверки. */
    data class InsufficientSplit(val trainingReviews: Int, val validationReviews: Int) : OptimizeOutcome

    /** Во время подбора пользователь сменил или сбросил параметры. */
    data object Superseded : OptimizeOutcome

    data class Done(
        val params: DoubleArray,
        /** Ошибка текущей и новой модели на отложенной истории (меньше — лучше). */
        val lossBefore: Double,
        val lossAfter: Double,
        val reviews: Int,
        val cards: Int,
        val validationReviews: Int,
    ) : OptimizeOutcome {
        val improved get() = validationReviews > 0 && lossBefore.isFinite() && lossAfter.isFinite() &&
            lossBefore > 0 && lossAfter >= 0 && lossAfter <= lossBefore * 0.999
        val improvementPercent get() = if (lossBefore > 0 && lossBefore.isFinite() && lossAfter.isFinite()) {
            ((1 - lossAfter / lossBefore) * 100).coerceAtLeast(0.0)
        } else 0.0
    }
}

/**
 * Подстройка параметров FSRS-6 под конкретного ученика.
 *
 * По журналу ответов восстанавливается история каждой карточки, и модель
 * памяти прогоняется заново с пробными параметрами. Для каждого повторения
 * через сутки и больше модель предсказывает вероятность вспомнить; ошибка —
 * бинарная кросс-энтропия с фактическим ответом («Снова» = забыл).
 * Параметры подбираются Adam с численным градиентом. Начальные стабильности
 * (w0–w3) оптимизируются в логарифмической шкале, у остальных — границы как
 * в эталонном оптимизаторе. Мягкая привязка к текущим значениям сдерживает
 * переобучение. Последние 20% повторений по времени не участвуют в подборе:
 * на них сравниваются текущие параметры и единственный выбранный кандидат.
 */
class FsrsOptimizer(
    events: List<ReviewEvent>,
    private val initial: DoubleArray = Fsrs.DEFAULT_W,
    /** Номер учебного дня: для повторений из REVIEW интервал считается в учебных днях, как в [Fsrs.review]. */
    private val studyDay: ((Long) -> Long)? = null,
) {

    private data class Step(val elapsed: Double, val rating: Rating, val time: Long)

    private val sequences: List<List<Step>> = events
        .groupBy { it.cardId }
        .values
        .flatMap { list -> splitAtResets(list.sortedBy { it.time }) }
        .map { sorted ->
            sorted.mapIndexed { i, e ->
                val prev = sorted.getOrNull(i - 1)
                val elapsed = when {
                    prev == null -> 0.0
                    e.stateBefore == CardState.REVIEW && studyDay != null ->
                        maxOf(0L, studyDay(e.time) - studyDay(prev.time)).toDouble()
                    else -> (e.time - prev.time).toDouble() / Fsrs.DAY
                }
                Step(elapsed, Rating.of(e.rating.coerceIn(1, 4)), e.time)
            }
        }
        .filter { it.size >= 2 }

    /** История карточки после сброса — отдельная последовательность: прежняя память к ней не относится. */
    private fun splitAtResets(sorted: List<ReviewEvent>): List<List<ReviewEvent>> {
        val result = mutableListOf<MutableList<ReviewEvent>>()
        for (e in sorted) {
            if (result.isEmpty() || (e.stateBefore == CardState.NEW && result.last().isNotEmpty())) result += mutableListOf<ReviewEvent>()
            result.last() += e
        }
        return result
    }

    private val reviewTimes = sequences.flatMap { seq -> seq.drop(1).filter { it.elapsed >= 1.0 }.map { it.time } }.sorted()
    /** Граница по времени: ответы после неё не используются даже для выбора итерации. */
    private val validationStart = reviewTimes.takeIf { it.size >= 2 }?.let { it[it.size - maxOf(1, it.size / 5)] }
    private val trainingSequences = sequences.map { seq -> seq.takeWhile { validationStart != null && it.time < validationStart } }
    val trainingItems: Int = reviewTimes.count { validationStart != null && it < validationStart }
    val validationItems: Int = reviewTimes.size - trainingItems

    val cards: Int get() = sequences.size

    fun loss(w: DoubleArray): Double = loss(w, Sample.All)

    internal fun trainingLoss(w: DoubleArray): Double = loss(w, Sample.Training)
    internal fun validationLoss(w: DoubleArray): Double = loss(w, Sample.Validation)

    private enum class Sample { All, Training, Validation }

    private fun loss(w: DoubleArray, sample: Sample): Double {
        val fsrs = Fsrs(w = w, enableFuzz = false)
        var total = 0.0
        var n = 0
        // При обучении не проигрываем и короткие шаги из отложенного периода.
        val histories = if (sample == Sample.Training) trainingSequences else sequences
        for (seq in histories) {
            var s = 0.0
            var d = 0.0
            seq.forEachIndexed { i, (elapsed, rating, time) ->
                if (i == 0) {
                    val (s0, d0) = fsrs.memoryStep(0.0, 0.0, isNew = true, elapsedDays = 0.0, rating = rating)
                    s = s0; d = d0
                    return@forEachIndexed
                }
                if (elapsed >= 1.0 && (sample != Sample.Validation || (validationStart != null && time >= validationStart))) {
                    val p = fsrs.recallProbability(elapsed, s).coerceIn(1e-4, 1 - 1e-4)
                    val y = if (rating == Rating.Again) 0.0 else 1.0
                    total += -(y * ln(p) + (1 - y) * ln(1 - p))
                    n++
                }
                val (s1, d1) = fsrs.memoryStep(s, d, isNew = false, elapsedDays = elapsed, rating = rating)
                s = s1; d = d1
            }
        }
        return if (n == 0) 0.0 else total / n
    }

    fun optimize(
        iterations: Int = 220,
        minItems: Int = MIN_ITEMS,
        onProgress: (Float) -> Unit = {},
    ): OptimizeOutcome {
        if (reviewTimes.size < minItems) return OptimizeOutcome.NotEnoughData(reviewTimes.size, minItems)
        val minValidation = maxOf(1, minItems / 5)
        if (trainingItems < maxOf(1, minItems - minValidation) || validationItems < minValidation) {
            return OptimizeOutcome.InsufficientSplit(trainingItems, validationItems)
        }

        val dims = initial.size
        val x0 = DoubleArray(dims) { toX(it, initial[it]) }
        val x = x0.copyOf()
        val scale = DoubleArray(dims) { i -> if (i < 4) 1.0 else (Fsrs.BOUNDS[i].endInclusive - Fsrs.BOUNDS[i].start) }
        // Чем меньше обучающих данных, тем сильнее держимся текущих значений.
        val lambda = 4.0 / trainingItems

        fun objective(v: DoubleArray): Double {
            var reg = 0.0
            for (i in 0 until dims) { val t = (v[i] - x0[i]) / scale[i]; reg += t * t }
            return trainingLoss(toW(v)) + lambda * reg
        }

        val m = DoubleArray(dims)
        val vv = DoubleArray(dims)
        val lr = 0.03
        val b1 = 0.9
        val b2 = 0.999
        var best = x.copyOf()
        var bestObj = objective(x)
        val grad = DoubleArray(dims)

        for (t in 1..iterations) {
            for (i in 0 until dims) {
                val h = 1e-4 * maxOf(1.0, abs(x[i]))
                val keep = x[i]
                x[i] = keep + h; val up = objective(x)
                x[i] = keep - h; val down = objective(x)
                x[i] = keep
                grad[i] = (up - down) / (2 * h)
            }
            for (i in 0 until dims) {
                m[i] = b1 * m[i] + (1 - b1) * grad[i]
                vv[i] = b2 * vv[i] + (1 - b2) * grad[i] * grad[i]
                val mh = m[i] / (1 - Math.pow(b1, t.toDouble()))
                val vh = vv[i] / (1 - Math.pow(b2, t.toDouble()))
                x[i] = clampX(i, x[i] - lr * mh / (sqrt(vh) + 1e-8))
            }
            val obj = objective(x)
            if (obj < bestObj) { bestObj = obj; best = x.copyOf() }
            onProgress(t.toFloat() / iterations)
        }

        // Проверяем те же округлённые значения, которые попадут в настройки.
        val params = Fsrs.decodeParams(Fsrs.encodeParams(toW(best)))!!
        return OptimizeOutcome.Done(
            params = params,
            lossBefore = validationLoss(initial),
            lossAfter = validationLoss(params),
            reviews = reviewTimes.size,
            cards = cards,
            validationReviews = validationItems,
        )
    }

    private fun toX(i: Int, w: Double) = if (i < 4) ln(w) else w
    private fun toW(x: DoubleArray) = DoubleArray(x.size) { i -> clampW(i, if (i < 4) exp(x[i]) else x[i]) }
    private fun clampW(i: Int, w: Double) = w.coerceIn(Fsrs.BOUNDS[i].start, Fsrs.BOUNDS[i].endInclusive)
    private fun clampX(i: Int, x: Double) = if (i < 4) {
        x.coerceIn(ln(Fsrs.BOUNDS[i].start), ln(Fsrs.BOUNDS[i].endInclusive))
    } else clampW(i, x)

    companion object {
        /** Минимум повторений с интервалом от суток, чтобы подстройка имела смысл. */
        const val MIN_ITEMS = 150
    }
}
