package nemallone.bworld.chat.filter.ai

internal enum class FilterCategory { ALLOW, ADVERTISING, TARGETED_INSULT }

// Оценка модели не равна измеренной точности
internal data class FilterVerdict(val category: FilterCategory, val confidence: Double) {
    init { require(confidence.isFinite() && confidence in 0.0..1.0) }
}
