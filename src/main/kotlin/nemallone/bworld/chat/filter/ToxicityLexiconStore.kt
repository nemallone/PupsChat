package nemallone.bworld.chat.filter

import java.io.File
import java.io.IOException
import java.io.InputStream

internal sealed interface InitialLexiconLoadResult {
    data object LoadedFromFile : InitialLexiconLoadResult

    data class LoadedFromFallback(
        val fileFailure: Exception
    ) : InitialLexiconLoadResult

    data class Failed(
        val fileFailure: Exception,
        val fallbackFailure: Exception
    ) : InitialLexiconLoadResult
}

internal class ToxicityLexiconStore(
    private val rulesFile: File,
    private val bundledRules: () -> InputStream?
) {
    @Volatile
    private var lexicon = ToxicityLexicon.empty()

    fun loadInitial(
        allowedWords: List<String>,
        warning: (String) -> Unit
    ): InitialLexiconLoadResult {
        val fileFailure = replaceFromFile(allowedWords, warning)
            ?: return InitialLexiconLoadResult.LoadedFromFile

        val fallbackRules = bundledRules()
            ?: return InitialLexiconLoadResult.Failed(
                fileFailure,
                IllegalStateException("Не найден встроенный toxicity.txt")
            )

        val fallbackFailure = replace(fallbackRules, allowedWords, warning)
        return if (fallbackFailure == null) {
            InitialLexiconLoadResult.LoadedFromFallback(fileFailure)
        } else {
            InitialLexiconLoadResult.Failed(fileFailure, fallbackFailure)
        }
    }

    fun reload(
        allowedWords: List<String>,
        warning: (String) -> Unit
    ): Exception? = replaceFromFile(allowedWords, warning)

    fun containsToxicity(message: String): Boolean {
        return ToxicityMatcher.containsToxicity(message, lexicon)
    }

    private fun replaceFromFile(
        allowedWords: List<String>,
        warning: (String) -> Unit
    ): Exception? {
        val rules = try {
            rulesFile.inputStream()
        } catch (exception: IOException) {
            return exception
        } catch (exception: SecurityException) {
            return exception
        }
        return replace(rules, allowedWords, warning)
    }

    private fun replace(
        rules: InputStream,
        allowedWords: List<String>,
        warning: (String) -> Unit
    ): Exception? {
        return try {
            lexicon = ToxicityLexicon.load(rules, allowedWords, warning)
            null
        } catch (exception: IOException) {
            exception
        } catch (exception: SecurityException) {
            exception
        }
    }
}
