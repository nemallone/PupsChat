package nemallone.bworld.chat.filter.ai

import nemallone.bworld.chat.PupsChat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor

internal class AiFilterManager(private val plugin: PupsChat) : AutoCloseable {
    private data class Active(val model: LocalFilterProvider, val threshold: Double)
    private var active: Active? = null
    private var checked = 0L
    private var matched = 0L

    fun loadConfig(): Boolean = try {
        val settings = AiFilterSettings.read(plugin.config)
        active = if (settings.enabled) {
            Active(LocalFilterProvider.load(plugin.dataFolder.toPath().resolve("ai-model.bin")), settings.threshold)
        } else null
        true
    } catch (_: Exception) {
        plugin.logger.warning("Не удалось загрузить ai-filter, сохранены предыдущие настройки")
        false
    }

    fun classify(text: String): FilterCategory? {
        val current = active ?: return null
        val verdict = current.model.classify(text)
        checked++
        if (verdict.category != FilterCategory.TARGETED_INSULT || verdict.confidence < current.threshold) {
            return null
        }
        matched++
        return verdict.category
    }

    fun status(): Component = Component.text(
        if (active == null) "ИИ-фильтр выключен" else "ИИ-фильтр включён. Проверено $checked, срабатываний $matched",
        TextColor.color(0xAAAAAA),
    )

    override fun close() { active = null }
}
