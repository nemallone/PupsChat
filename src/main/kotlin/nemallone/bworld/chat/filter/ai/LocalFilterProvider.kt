package nemallone.bworld.chat.filter.ai

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

internal class LocalFilterProvider private constructor(
    private val weights: FloatArray,
    private val bias: FloatArray,
) {
    fun classify(message: String): FilterVerdict {
        val normalized = normalize(message)
        if (normalized.isEmpty()) return FilterVerdict(FilterCategory.ALLOW, 1.0)

        val counts = IntArray(DIMENSIONS)
        val active = IntArray(MAX_FEATURES)
        var activeCount = 0
        fun add(feature: String) {
            val index = hash(feature)
            if (counts[index] == 0) {
                if (activeCount == MAX_FEATURES) return
                active[activeCount++] = index
            }
            counts[index]++
        }

        val words = normalized.split(' ')
        var previous: String? = null
        for (word in words) {
            add("w:$word")
            previous?.let { add("b:$it $word") }
            previous = word
            val bounded = "^$word$"
            for (start in bounded.indices) {
                for (length in 3..5) {
                    if (start + length <= bounded.length) add("c:${bounded.substring(start, start + length)}")
                }
            }
        }

        val values = DoubleArray(activeCount)
        var squaredNorm = 0.0
        for (position in 0 until activeCount) {
            val value = 1.0 + ln(counts[active[position]].toDouble())
            values[position] = value
            squaredNorm += value * value
        }
        val norm = sqrt(squaredNorm)
        val scores = DoubleArray(CLASS_COUNT) { bias[it].toDouble() }
        for (position in 0 until activeCount) {
            val value = values[position] / norm
            val index = active[position]
            for (label in 0 until CLASS_COUNT) scores[label] += weights[label * DIMENSIONS + index] * value
        }

        val maximum = scores.max()
        var denominator = 0.0
        for (label in scores.indices) {
            scores[label] = exp(scores[label] - maximum)
            denominator += scores[label]
        }
        val winner = scores.indices.maxBy { scores[it] }
        return FilterVerdict(CATEGORIES[winner], scores[winner] / denominator)
    }

    companion object {
        private const val DIMENSIONS = 16_384
        private const val MAX_LENGTH = 512
        private const val MAX_FEATURES = 2_048
        private const val CLASS_COUNT = 3
        private val CATEGORIES = arrayOf(FilterCategory.ALLOW, FilterCategory.ADVERTISING, FilterCategory.TARGETED_INSULT)

        fun load(modelFile: Path? = null): LocalFilterProvider {
            val resource = if (modelFile != null && Files.exists(modelFile)) {
                require(Files.size(modelFile) == 196_644L) { "Некорректный размер ai-model.bin" }
                Files.newInputStream(modelFile)
            } else LocalFilterProvider::class.java.getResourceAsStream("/ai/local-model.bin")
                ?: error("Встроенная модель ИИ отсутствует в jar")
            return DataInputStream(BufferedInputStream(resource)).use { input ->
                require(input.readNBytes(8).contentEquals("PCHATML1".toByteArray(Charsets.US_ASCII))) {
                    "Неизвестный формат встроенной модели ИИ"
                }
                require(input.readInt() == DIMENSIONS && input.readInt() == MAX_LENGTH &&
                    input.readInt() == MAX_FEATURES && input.readInt() == CLASS_COUNT) {
                    "Несовместимая встроенная модель ИИ"
                }
                val bias = FloatArray(CLASS_COUNT) { input.readFloat().also { require(it.isFinite()) } }
                val weights = FloatArray(DIMENSIONS * CLASS_COUNT) { input.readFloat().also { require(it.isFinite()) } }
                require(input.read() == -1) { "Некорректный размер встроенной модели ИИ" }
                LocalFilterProvider(weights, bias)
            }
        }

        private fun normalize(message: String): String {
            val result = StringBuilder(MAX_LENGTH)
            for (raw in message.take(MAX_LENGTH).lowercase(Locale.ROOT).take(MAX_LENGTH)) {
                val character = if (raw == 'ё') 'е' else raw
                when {
                    character in 'a'..'z' || character in 'а'..'я' || character in '0'..'9' -> result.append(character)
                    result.isNotEmpty() && result.last() != ' ' -> result.append(' ')
                }
            }
            return result.trim().toString()
        }

        private fun hash(feature: String): Int {
            var value = 0x811c9dc5.toInt()
            for (character in feature) value = (value xor character.code) * 16_777_619
            return value and (DIMENSIONS - 1)
        }
    }
}
