package nemallone.bworld.chat.logging

import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.logging.Logger
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal enum class ChatLogType(val fileName: String, val yamlKey: String, val label: String) {
    MESSAGES("messages.yml", "messages", "сообщений"),
    COMMANDS("commands.yml", "commands", "команд"),
    STAFF("staff.yml", "staff", "сообщений сотрудников"),
}

internal data class ChatLogEntry(
    val type: ChatLogType,
    val playerName: String,
    val ip: String,
    val text: String,
    val timestamp: Long,
    val channelId: String? = null,
    val outcome: String? = null,
)

internal fun routedLogType(channelId: String): ChatLogType =
    if (channelId.equals("staff", ignoreCase = true)) ChatLogType.STAFF else ChatLogType.MESSAGES

internal fun routedLogMetadata(channelId: String?, outcome: String?): String {
    fun safe(value: String): String = value.take(64).map {
        if (it.isLetterOrDigit() || it in "_-.") it else '_'
    }.joinToString("")
    return buildString {
        if (channelId != null) append("[channel: ").append(safe(channelId)).append("] ")
        if (outcome != null) append("[outcome: ").append(safe(outcome)).append("] ")
    }
}

internal data class ChatLogOptions(
    val enabled: Boolean,
    val flushIntervalMillis: Long,
    val maxLatestBytes: Long,
    val maxTotalBytes: Long,
    val retentionMillis: Long,
)

internal class ChatLogStore(
    private val root: Path,
    private val startedAt: Long,
    private val logger: Logger,
    initialOptions: ChatLogOptions,
) {

    private val sessionDirectory = root.resolve(SESSION_DIRECTORY.format(Instant.ofEpochMilli(startedAt)))
    private val queue = ArrayBlockingQueue<ChatLogEntry>(QUEUE_CAPACITY)
    private val accepting = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private val warningAt = AtomicLong(0L)
    private val shutdownDropped = AtomicInteger(0)
    private val inFlightEntries = AtomicInteger(0)
    private val offerLock = Any()
    private val playerNames = ConcurrentSkipListMap<String, String>()
    private val trackedPlayerNameCount = AtomicInteger(0)
    private val maintenanceLock = Any()
    private val readers = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(READER_QUEUE_CAPACITY),
        { task -> Thread(task, "PupsChat-LogReader").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val openWriters = object : LinkedHashMap<Path, BufferedWriter>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, BufferedWriter>?): Boolean {
            if (size <= MAX_OPEN_FILES || eldest == null) return false
            runCatching { eldest.value.flush() }.onFailure { beginIoBackoff() }
            runCatching { eldest.value.close() }
            return true
        }
    }
    @Volatile
    private var options = initialOptions

    private val totalBytes = AtomicLong(0L)
    @Volatile
    private var capacityRetryAt = 0L
    @Volatile
    private var ioRetryAt = 0L

    private val writerThread = Thread(::writerLoop, "PupsChat-Logs").apply {
        isDaemon = true
        start()
    }

    fun reload(newOptions: ChatLogOptions) {
        options = newOptions
        capacityRetryAt = 0L
        ioRetryAt = 0L
        LockSupport.unpark(writerThread)
    }

    fun offer(entry: ChatLogEntry) {
        if (!options.enabled) return
        registerPlayerName(entry.playerName)
        val accepted = synchronized(offerLock) {
            accepting.get() && queue.offer(entry)
        }
        if (accepted) {
            LockSupport.unpark(writerThread)
        } else if (accepting.get()) {
            warnRateLimited("Очередь логов заполнена; часть записей пропущена")
        }
    }

    fun registerPlayerName(name: String) {
        if (!PLAYER_NAME.matches(name)) return
        val key = name.lowercase(Locale.ROOT)
        if (trackedPlayerNameCount.get() >= MAX_TRACKED_PLAYER_NAMES && !playerNames.containsKey(key)) return
        if (playerNames.putIfAbsent(key, name) == null &&
            trackedPlayerNameCount.incrementAndGet() > MAX_TRACKED_PLAYER_NAMES
        ) {
            if (playerNames.remove(key, name)) trackedPlayerNameCount.decrementAndGet()
        }
    }

    fun suggestPlayerNames(input: String): List<String> {
        val prefix = input.lowercase(Locale.ROOT)
        return playerNames.tailMap(prefix, true).entries.asSequence()
            .takeWhile { it.key.startsWith(prefix) }
            .take(MAX_PLAYER_SUGGESTIONS)
            .map(Map.Entry<String, String>::value)
            .toList()
    }

    fun readLatest(type: ChatLogType, playerName: String?, amount: Int): CompletableFuture<List<String>> =
        submitRead {
            synchronized(maintenanceLock) {
                val paths = if (playerName == null) findGlobalLogs(type) else findPlayerLogs(playerName, type)
                readLastEntries(paths, amount)
            }
        }

    fun clearOlderThan(cutoff: Long): CompletableFuture<Int> = submitRead {
        synchronized(maintenanceLock) { deleteEligible(cutoff) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(offerLock) { accepting.set(false) }
        readers.shutdownNow()
        runCatching { readers.awaitTermination(1, TimeUnit.SECONDS) }
        LockSupport.unpark(writerThread)
        runCatching { writerThread.join(SHUTDOWN_TIMEOUT_MS) }
        if (writerThread.isAlive) {
            writerThread.interrupt()
            runCatching { writerThread.join(INTERRUPT_JOIN_TIMEOUT_MS) }
        }
        val lost = synchronized(offerLock) {
            shutdownDropped.get() + inFlightEntries.get() + queue.size
        }
        if (writerThread.isAlive || lost > 0) {
            logger.warning("Запись логов не завершилась вовремя; могло быть пропущено записей: $lost")
        }
    }

    private fun writerLoop() {
        var initialized = false
        try {
            Files.createDirectories(root)
            synchronized(maintenanceLock) {
                rotateLatest(startedAt)
                cleanup()
                indexPlayerNames()
            }
            initialized = true
            val batch = ArrayList<ChatLogEntry>(BATCH_SIZE)
            while (accepting.get() || queue.isNotEmpty()) {
                if (Thread.currentThread().isInterrupted) {
                    synchronized(offerLock) {
                        shutdownDropped.addAndGet(queue.size)
                        queue.clear()
                    }
                    break
                }
                batch.clear()
                val first = queue.poll()
                if (first == null && accepting.get()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(options.flushIntervalMillis))
                    continue
                }
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, BATCH_SIZE - 1)
                }
                if (batch.isEmpty()) continue
                inFlightEntries.set(batch.size)
                synchronized(maintenanceLock) {
                    for (index in batch.indices) {
                        if (Thread.currentThread().isInterrupted) break
                        appendSafely(batch[index])
                    }
                    flushWriters()
                }
                if (Thread.currentThread().isInterrupted) {
                    synchronized(offerLock) {
                        val pending = inFlightEntries.get()
                        shutdownDropped.addAndGet(pending)
                        inFlightEntries.addAndGet(-pending)
                    }
                } else {
                    inFlightEntries.set(0)
                }
            }
        } catch (failure: Throwable) {
            synchronized(offerLock) {
                accepting.set(false)
                val pending = inFlightEntries.get()
                shutdownDropped.addAndGet(pending + queue.size)
                inFlightEntries.addAndGet(-pending)
                queue.clear()
            }
            warnRateLimited("Хранилище логов недоступно: ${failure.message}")
        } finally {
            closeWriters()
            if (initialized && !Thread.currentThread().isInterrupted) runCatching {
                synchronized(maintenanceLock) {
                    rotateLatest(System.currentTimeMillis())
                    cleanup()
                }
            }.onFailure { warnRateLimited("Не удалось завершить ротацию логов: ${it.message}") }
        }
    }

    private fun appendSafely(entry: ChatLogEntry) {
        try {
            synchronized(maintenanceLock) {
                if (!storageAvailable()) {
                    warnRateLimited("Хранилище логов временно недоступно; новые записи пропускаются")
                    return
                }
                val time = ENTRY_TIME.format(Instant.ofEpochMilli(entry.timestamp))
                val ip = entry.ip.ifBlank { "unknown" }
                val metadata = routedLogMetadata(entry.channelId, entry.outcome)
                val playerValue = "[$time] [IP: $ip] $metadata${entry.text}"
                val allValue = "[$time] [Игрок: ${entry.playerName}] [IP: $ip] $metadata${entry.text}"
                val playerPath = sessionDirectory.resolve(safeName(entry.playerName)).resolve(entry.type.fileName)
                val latestPath = root.resolve("all-${entry.type.yamlKey}-latest.yml")
                val required = encodedLineSize(playerValue) + encodedLineSize(allValue) + HEADER_RESERVE_BYTES
                if (!reserveDisk(required)) {
                    warnRateLimited("Достигнут лимит размера логов; новые записи временно пропускаются")
                    return
                }
                rotateLatestIfFull(entry.type, latestPath, encodedLineSize(allValue))
                appendYaml(playerPath, entry.type.yamlKey, playerValue)
                appendYaml(latestPath, entry.type.yamlKey, allValue)
            }
        } catch (failure: Throwable) {
            beginIoBackoff()
            warnRateLimited("Не удалось записать лог ${entry.type.yamlKey}: ${failure.message}")
            closeWriters()
        }
    }

    private fun appendYaml(path: Path, key: String, value: String) {
        val wasOpen = path in openWriters
        val writer = writer(path)
        val existed = wasOpen || Files.isRegularFile(path) && Files.size(path) > 0L
        if (!existed) {
            val header = "$key:\n"
            writer.append(header)
            totalBytes.addAndGet(header.toByteArray(StandardCharsets.UTF_8).size.toLong())
        }
        val line = "  - \"${escapeYaml(value)}\"\n"
        writer.append(line)
        totalBytes.addAndGet(line.toByteArray(StandardCharsets.UTF_8).size.toLong())
    }

    private fun writer(path: Path): BufferedWriter = openWriters[path] ?: run {
        Files.createDirectories(path.parent)
        repairIncompleteTail(path)
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        ).also { openWriters[path] = it }
    }

    private fun flushWriters() {
        val failed = openWriters.entries.filter { runCatching { it.value.flush() }.isFailure }.map { it.key }
        if (failed.isNotEmpty()) {
            beginIoBackoff()
            warnRateLimited("Не удалось сбросить часть файлов логов на диск")
        }
        for (path in failed) closeWriter(path)
    }

    private fun storageAvailable(): Boolean {
        val retryAt = ioRetryAt
        if (retryAt == 0L) return true
        if (System.currentTimeMillis() < retryAt) return false
        closeWriters()
        return runCatching {
            totalBytes.set(directorySize(root))
            ioRetryAt = 0L
            true
        }.getOrElse {
            beginIoBackoff()
            false
        }
    }

    private fun beginIoBackoff() {
        ioRetryAt = System.currentTimeMillis() + IO_RETRY_MS
    }

    private fun repairIncompleteTail(path: Path) {
        if (!Files.isRegularFile(path)) return
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val originalSize = channel.size()
            if (originalSize == 0L) return
            val last = ByteBuffer.allocate(1)
            if (channel.read(last, originalSize - 1L) == 1 && last.array()[0] == '\n'.code.toByte()) return

            var position = originalSize
            var completeSize = 0L
            val buffer = ByteBuffer.allocate(READ_CHUNK_SIZE)
            search@ while (position > 0L) {
                val start = (position - READ_CHUNK_SIZE).coerceAtLeast(0L)
                buffer.clear()
                buffer.limit((position - start).toInt())
                val read = channel.read(buffer, start)
                if (read <= 0) break
                val bytes = buffer.array()
                for (index in read - 1 downTo 0) {
                    if (bytes[index] == '\n'.code.toByte()) {
                        completeSize = start + index + 1L
                        break@search
                    }
                }
                position = start
            }
            channel.truncate(completeSize)
            val removed = originalSize - completeSize
            totalBytes.updateAndGet { current -> (current - removed).coerceAtLeast(0L) }
        }
    }

    private fun closeWriter(path: Path) {
        val writer = openWriters.remove(path) ?: return
        runCatching { writer.flush() }
        runCatching { writer.close() }
    }

    private fun closeWriters() {
        openWriters.values.forEach {
            runCatching { it.flush() }
            runCatching { it.close() }
        }
        openWriters.clear()
    }

    private fun reserveDisk(required: Long): Boolean {
        val limit = options.maxTotalBytes
        if (limit <= 0L) return true
        if (required > limit) return false
        if (totalBytes.get() <= limit - required) return true
        val now = System.currentTimeMillis()
        if (now < capacityRetryAt) return false
        flushWriters()
        synchronized(maintenanceLock) {
            cleanup(required)
        }
        val available = totalBytes.get() <= limit - required
        capacityRetryAt = if (available) 0L else now + CAPACITY_RETRY_MS
        return available
    }

    private fun rotateLatestIfFull(type: ChatLogType, path: Path, nextLineBytes: Long) {
        val limit = options.maxLatestBytes
        if (limit <= 0L) return
        val currentSize = if (Files.isRegularFile(path)) Files.size(path) else 0L
        if (currentSize == 0L || currentSize + nextLineBytes <= limit) return
        closeWriter(path)
        synchronized(maintenanceLock) { rotate(type, System.currentTimeMillis()) }
    }

    private fun rotateLatest(timestamp: Long) {
        for (type in ChatLogType.entries) rotate(type, timestamp)
    }

    private fun rotate(type: ChatLogType, timestamp: Long) {
        val latest = root.resolve("all-${type.yamlKey}-latest.yml")
        if (!Files.isRegularFile(latest)) return
        closeWriter(latest)
        repairIncompleteTail(latest)
        if (Files.size(latest) == 0L) {
            Files.deleteIfExists(latest)
            return
        }
        val suffix = ARCHIVE_TIME.format(Instant.ofEpochMilli(timestamp))
        var archive = root.resolve("all-${type.yamlKey}-$suffix.yml")
        var sequence = 2
        while (Files.exists(archive)) {
            archive = root.resolve("all-${type.yamlKey}-$suffix-$sequence.yml")
            sequence++
        }
        val size = Files.size(latest)
        try {
            Files.move(latest, archive, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(latest, archive)
        }
        val archiveSize = Files.size(archive)
        totalBytes.updateAndGet { current -> (current - size).coerceAtLeast(0L) + archiveSize }
    }

    private fun cleanup(reservedBytes: Long = 0L) {
        val retention = options.retentionMillis
        if (retention > 0L) deleteEligible(System.currentTimeMillis() - retention)
        val limit = options.maxTotalBytes
        var size = directorySize(root)
        if (limit <= 0L) {
            totalBytes.set(size)
            return
        }
        val targetSize = (limit - reservedBytes).coerceAtLeast(0L)
        if (size <= targetSize) {
            totalBytes.set(size)
            return
        }
        for (path in eligibleEntries().sortedBy(::entryLastModified)) {
            val removed = pathSize(path)
            deleteTree(path)
            size = (size - removed).coerceAtLeast(0L)
            if (size <= targetSize) break
        }
        totalBytes.set(size)
    }

    private fun deleteEligible(cutoff: Long): Int {
        var deleted = 0
        for (path in eligibleEntries()) {
            if (Thread.currentThread().isInterrupted) break
            if (entryLastModified(path) >= cutoff) continue
            val removed = pathSize(path)
            deleteTree(path)
            totalBytes.updateAndGet { current -> (current - removed).coerceAtLeast(0L) }
            deleted++
        }
        return deleted
    }

    private fun eligibleEntries(): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { path ->
                if (path == sessionDirectory) return@filter false
                val name = path.fileName.toString()
                path.isDirectory() && SESSION_NAME.matches(name) ||
                    path.isRegularFile() && ARCHIVE_NAME.matches(name)
            }.toList()
        }
    }

    private fun indexPlayerNames() {
        if (!Files.isDirectory(root)) return
        val sessionDirectories = Files.list(root).use { directories ->
            directories.filter { it.isDirectory() && SESSION_NAME.matches(it.fileName.toString()) }.toList()
        }
        val repairDirectory = sessionDirectories.maxByOrNull {
            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L)
        }
        sessionDirectories.forEach { directory ->
            Files.list(directory).use { players ->
                players
                    .filter(Path::isDirectory)
                    .forEach { playerDirectory ->
                        val playerName = playerDirectory.fileName.toString()
                        if (PLAYER_NAME.matches(playerName)) {
                            registerPlayerName(playerName)
                            if (directory == repairDirectory) {
                                ChatLogType.entries.forEach { type ->
                                    val path = playerDirectory.resolve(type.fileName)
                                    runCatching {
                                        repairIncompleteTail(path)
                                        if (Files.isRegularFile(path) && Files.size(path) == 0L) {
                                            Files.deleteIfExists(path)
                                        }
                                    }.onFailure {
                                        warnRateLimited(
                                            "Не удалось восстановить старый лог ${path.fileName}: ${it.message}",
                                        )
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    private fun findGlobalLogs(type: ChatLogType): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val latest = root.resolve("all-${type.yamlKey}-latest.yml").takeIf(Files::isRegularFile)
        val prefix = "all-${type.yamlKey}-"
        val archives = Files.list(root).use { paths ->
            paths.filter { path ->
                path.isRegularFile() && ARCHIVE_NAME.matches(path.fileName.toString()) &&
                    path.fileName.toString().startsWith(prefix)
            }.sorted(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() }.reversed()).toList()
        }
        return listOfNotNull(latest) + archives
    }

    private fun findPlayerLogs(playerName: String, type: ChatLogType): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val matches = ArrayList<Path>()
        Files.list(root).use { directories ->
            directories.filter { it.isDirectory() && SESSION_NAME.matches(it.fileName.toString()) }
                .forEach { directory ->
                    Files.list(directory).use { players ->
                        players.filter { path ->
                            path.isDirectory() && path.fileName.toString().equals(playerName, ignoreCase = true)
                        }.map { it.resolve(type.fileName) }
                            .filter(Path::isRegularFile)
                            .forEach(matches::add)
                    }
                }
        }
        return matches.sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
    }

    private fun readLastEntries(paths: List<Path>, amount: Int): List<String> {
        val chunks = ArrayDeque<List<String>>()
        var remaining = amount
        for (path in paths) {
            if (remaining <= 0) break
            val entries = readLastEntries(path, remaining)
            if (entries.isEmpty()) continue
            chunks.addFirst(entries)
            remaining -= entries.size
        }
        return chunks.flatten()
    }

    private fun readLastEntries(path: Path, amount: Int): List<String> {
        repairIncompleteTail(path)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            var position = channel.size()
            val reversedLine = ByteArrayOutputStream(256)
            val entries = ArrayList<String>(amount)
            val buffer = ByteBuffer.allocate(READ_CHUNK_SIZE)
            while (position > 0 && entries.size < amount) {
                val start = (position - READ_CHUNK_SIZE).coerceAtLeast(0L)
                buffer.clear()
                buffer.limit((position - start).toInt())
                val read = channel.read(buffer, start)
                if (read <= 0) break
                val bytes = buffer.array()
                for (index in read - 1 downTo 0) {
                    val byte = bytes[index]
                    if (byte == '\n'.code.toByte()) {
                        decodeReversedLine(reversedLine)?.let(entries::add)
                        reversedLine.reset()
                        if (entries.size >= amount) break
                    } else if (byte != '\r'.code.toByte()) {
                        reversedLine.write(byte.toInt())
                    }
                }
                position = start
            }
            if (position == 0L && entries.size < amount) decodeReversedLine(reversedLine)?.let(entries::add)
            entries.reverse()
            return entries
        }
    }

    private fun decodeReversedLine(buffer: ByteArrayOutputStream): String? {
        if (buffer.size() == 0) return null
        val reversed = buffer.toByteArray()
        reversed.reverse()
        return decodeYamlLine(String(reversed, StandardCharsets.UTF_8))
    }

    private fun <T> submitRead(block: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (!accepting.get()) {
            result.completeExceptionally(IllegalStateException("Хранилище логов закрыто"))
            return result
        }
        try {
            readers.execute {
                try {
                    result.complete(block())
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (failure: RejectedExecutionException) {
            result.completeExceptionally(failure)
        }
        return result
    }

    private fun directorySize(path: Path): Long {
        if (!Files.exists(path)) return 0L
        return Files.walk(path).use { paths ->
            paths.filter(Path::isRegularFile).mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }

    private fun pathSize(path: Path): Long = if (path.isRegularFile()) Files.size(path) else directorySize(path)

    private fun entryLastModified(path: Path): Long {
        if (path.isRegularFile()) return Files.getLastModifiedTime(path).toMillis()
        return Files.walk(path).use { paths ->
            paths.mapToLong { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                .max()
                .orElse(0L)
        }
    }

    private fun deleteTree(path: Path) {
        if (!path.isDirectory()) {
            Files.deleteIfExists(path)
            return
        }
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun encodedLineSize(value: String): Long =
        (escapeYaml(value).toByteArray(StandardCharsets.UTF_8).size + YAML_LINE_OVERHEAD_BYTES).toLong()

    private fun warnRateLimited(message: String) {
        val now = System.currentTimeMillis()
        val previous = warningAt.get()
        if (now - previous >= WARNING_INTERVAL_MS && warningAt.compareAndSet(previous, now)) logger.warning(message)
    }

    private fun safeName(name: String): String = name.replace(UNSAFE_NAME, "_")

    private fun escapeYaml(value: String): String = buildString(value.length + 16) {
        for (character in value) when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }

    private fun decodeYamlLine(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("- \"") || !trimmed.endsWith('"')) return null
        val value = trimmed.substring(3, trimmed.length - 1)
        return buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (character != '\\' || index >= value.length) {
                    append(character)
                    continue
                }
                when (val escaped = value[index++]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'u' -> {
                        val end = (index + 4).coerceAtMost(value.length)
                        val hex = value.substring(index, end)
                        val decoded = if (hex.length == 4) hex.toIntOrNull(16) else null
                        if (decoded == null) append("\\u").append(hex) else append(decoded.toChar())
                        index = end
                    }
                    else -> append(escaped)
                }
            }
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 4_096
        const val READER_QUEUE_CAPACITY = 16
        const val BATCH_SIZE = 256
        const val MAX_OPEN_FILES = 64
        const val MAX_PLAYER_SUGGESTIONS = 100
        const val MAX_TRACKED_PLAYER_NAMES = 100_000
        const val READ_CHUNK_SIZE = 8_192
        const val YAML_LINE_OVERHEAD_BYTES = 16
        const val HEADER_RESERVE_BYTES = 32L
        const val WARNING_INTERVAL_MS = 60_000L
        const val CAPACITY_RETRY_MS = 30_000L
        const val IO_RETRY_MS = 30_000L
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
        const val INTERRUPT_JOIN_TIMEOUT_MS = 1_000L
        val ZONE: ZoneId = ZoneId.systemDefault()
        val SESSION_DIRECTORY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss").withZone(ZONE)
        val ENTRY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE)
        val ARCHIVE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss").withZone(ZONE)
        val SESSION_NAME = Regex("\\d{2}-\\d{2}-\\d{4}-\\d{2}-\\d{2}-\\d{2}")
        val ARCHIVE_NAME = Regex("all-(?:messages|commands|staff)-\\d{2}-\\d{2}-\\d{4}-\\d{2}-\\d{2}-\\d{2}(?:-\\d+)?\\.yml")
        val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        val UNSAFE_NAME = Regex("[^A-Za-z0-9_]")
    }
}
