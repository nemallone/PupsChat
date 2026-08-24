package nemallone.bworld.chat

import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

internal class PlayerDataException(
    val path: Path,
    message: String,
    cause: Exception
) : RuntimeException(message, cause)

internal class PlayerDataSaver(
    private val directory: Path,
    private val logger: Logger
) : AutoCloseable {
    private val stateLock = Any()
    private val pendingWrites = LinkedHashMap<String, (YamlConfiguration) -> Unit>()
    private val failedFiles = HashSet<String>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PupsChat-player-data").apply { isDaemon = true }
    }

    private var workerScheduled = false
    private var closed = false

    init {
        try {
            Files.createDirectories(directory)
        } catch (exception: IOException) {
            throw PlayerDataException(
                directory,
                "Не удалось создать папку данных игроков",
                exception
            )
        } catch (exception: SecurityException) {
            throw PlayerDataException(
                directory,
                "Нет доступа к папке данных игроков",
                exception
            )
        }
    }

    fun load(fileName: String): YamlConfiguration? {
        val path = directory.resolve(fileName)
        if (!Files.exists(path)) return null

        val configuration = YamlConfiguration()
        try {
            configuration.load(path.toFile())
        } catch (exception: IOException) {
            throw PlayerDataException(path, "Не удалось прочитать $fileName", exception)
        } catch (exception: InvalidConfigurationException) {
            throw PlayerDataException(path, "Некорректный YAML в $fileName", exception)
        } catch (exception: SecurityException) {
            throw PlayerDataException(path, "Нет доступа к $fileName", exception)
        }
        return configuration
    }

    /**
     * Объединяет ожидающие записи одного файла. [populate] выполняется в потоке writer
     * и должна использовать только неизменяемый снимок, подготовленный вызывающим кодом.
     */
    fun save(fileName: String, populate: (YamlConfiguration) -> Unit) {
        val schedulingFailure = synchronized(stateLock) {
            if (closed) {
                logger.warning("Попытка сохранить $fileName после остановки хранилища")
                return
            }

            pendingWrites[fileName] = populate
            if (workerScheduled) null else scheduleWorkerLocked()
        }

        if (schedulingFailure != null) {
            logger.log(
                Level.WARNING,
                "Не удалось запустить сохранение данных игроков",
                schedulingFailure
            )
        }
    }

    private fun scheduleWorkerLocked(): RejectedExecutionException? {
        workerScheduled = true
        return try {
            executor.execute(::drainWrites)
            null
        } catch (exception: RejectedExecutionException) {
            workerScheduled = false
            failedFiles.addAll(pendingWrites.keys)
            exception
        }
    }

    private fun drainWrites() {
        while (true) {
            val next = takeNextWrite() ?: return
            try {
                writeAtomically(next.first, next.second)
                synchronized(stateLock) {
                    failedFiles.remove(next.first)
                }
            } catch (exception: IOException) {
                recordWriteFailure(next.first, exception)
            } catch (exception: RuntimeException) {
                recordWriteFailure(next.first, exception)
            } catch (error: Error) {
                recordWriteFailure(next.first, error)
                synchronized(stateLock) {
                    workerScheduled = false
                }
                throw error
            }
        }
    }

    private fun takeNextWrite(): Pair<String, (YamlConfiguration) -> Unit>? {
        synchronized(stateLock) {
            val iterator = pendingWrites.entries.iterator()
            if (!iterator.hasNext()) {
                workerScheduled = false
                return null
            }

            val entry = iterator.next()
            val write = entry.key to entry.value
            iterator.remove()
            return write
        }
    }

    private fun recordWriteFailure(fileName: String, exception: Throwable) {
        synchronized(stateLock) {
            failedFiles.add(fileName)
        }
        logger.log(Level.WARNING, "Не удалось сохранить $fileName", exception)
    }

    private fun writeAtomically(
        fileName: String,
        populate: (YamlConfiguration) -> Unit
    ) {
        val configuration = YamlConfiguration()
        populate(configuration)
        val contents = configuration.saveToString()

        val target = directory.resolve(fileName)
        val temporary = Files.createTempFile(directory, ".$fileName.", ".tmp")
        try {
            Files.writeString(
                temporary,
                contents,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (exception: IOException) {
                logger.log(Level.WARNING, "Не удалось удалить временный файл $temporary", exception)
            } catch (exception: SecurityException) {
                logger.log(Level.WARNING, "Нет доступа к временному файлу $temporary", exception)
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        executor.shutdown()

        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.severe(
                    "Не удалось завершить сохранение данных игроков за " +
                        "$SHUTDOWN_TIMEOUT_SECONDS секунд"
                )
                executor.shutdownNow()
                if (!executor.awaitTermination(
                        FORCED_SHUTDOWN_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                ) {
                    logger.severe(
                        "Поток сохранения данных игроков продолжает работать после остановки"
                    )
                }
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            var terminated = false
            try {
                terminated = executor.awaitTermination(
                    FORCED_SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            } catch (_: InterruptedException) {
                // статус прерывания восстанавливается после ограниченной попытки завершения
            }
            Thread.currentThread().interrupt()
            logger.warning("Ожидание остановки хранилища данных было прервано")
            if (!terminated) {
                logger.severe(
                    "Поток сохранения данных игроков продолжает работать после остановки"
                )
            }
        }

        synchronized(stateLock) {
            if (pendingWrites.isNotEmpty()) {
                logger.severe(
                    "При остановке не сохранены файлы: ${pendingWrites.keys.joinToString()}"
                )
            }
            if (failedFiles.isNotEmpty()) {
                logger.severe(
                    "Последняя запись завершилась ошибкой: ${failedFiles.joinToString()}"
                )
            }
        }
    }

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 10L
        const val FORCED_SHUTDOWN_TIMEOUT_SECONDS = 2L
    }
}
