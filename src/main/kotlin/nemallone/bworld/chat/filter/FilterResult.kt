package nemallone.bworld.chat.filter

internal sealed interface FilterResult {

    data object Allowed : FilterResult
    data class Modified(val message: String) : FilterResult
    data object Blocked : FilterResult

}
