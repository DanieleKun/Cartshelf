package app.archivefetcher.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A file saved for later, with everything needed to download it without loading its console again. */
@Serializable
data class LaterItem(
    val consoleId: String,
    val consoleName: String,
    val identifier: String,
    val path: String,
    val size: Long,
    val md5: String? = null,
    val locked: Boolean = false,
    val format: String = "",
    val packTitle: String = "",
) {
    val key: String get() = "$identifier/$path"

    fun toRomFile(): RomFile = RomFile.create(identifier, path, size, format, packTitle, locked, md5)

    companion object {
        fun of(consoleId: String, consoleName: String, file: RomFile) = LaterItem(
            consoleId, consoleName, file.identifier, file.path, file.size, file.md5, file.locked, file.format, file.packTitle,
        )
    }
}

/** The "download later" list, kept in one small JSON file and replaced atomically on every change. */
class LaterStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<LaterItem>> = _items.asStateFlow()

    /** Adds the items that are not in the list yet; returns how many were new. */
    fun add(new: List<LaterItem>): Int {
        var added = 0
        _items.update { current ->
            val known = current.mapTo(HashSet()) { it.key }
            val fresh = new.filter { known.add(it.key) }
            added = fresh.size
            current + fresh
        }
        persist()
        return added
    }

    fun remove(keys: Set<String>) {
        _items.update { list -> list.filterNot { it.key in keys } }
        persist()
    }

    fun clear() {
        _items.value = emptyList()
        persist()
    }

    private fun read(): List<LaterItem> = runCatching {
        if (file.exists()) json.decodeFromString<List<LaterItem>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun persist() {
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(_items.value))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }
}
