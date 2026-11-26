package social.hotmess.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Help history: the help requests the user sent and the places shared with them in those threads (a food bank, a
 * shelter, a line), kept on this phone only so they can find them again after the alert closes and the API stops
 * showing it. Nothing here goes to the server.
 *
 * DV and stalking resources ([SharedResource.sensitive]) are never kept, so nothing on the phone shows one was shared.
 * Message text isn't kept either: only what the request was about, where, when, and the shared places. The app keeps
 * the file in `noBackupFilesDir`. [clear] ("Clear history") removes it, and so does signing out.
 */
class HelpHistory(private val file: File?) {
    @Serializable
    data class Entry(
        val id: String,
        val venueName: String? = null,
        val summary: String = "",
        val createdAt: String,
        val resources: List<SharedResource> = emptyList(),
    )

    private val _entries = MutableStateFlow(read())
    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Keeps [alert] (or brings its entry up to date): its summary, venue and shared places, minus any sensitive one. */
    @Synchronized
    fun record(alert: SafetyAlert) {
        val current = _entries.value
        val existing = current.firstOrNull { it.id == alert.id }
        val known = existing?.resources.orEmpty()
        val added = alert.messages.mapNotNull { it.resource }.filter { !it.sensitive && known.none { k -> k.id == it.id } }
            .distinctBy { it.id }
        val entry = Entry(alert.id, alert.venue.name, alert.summary, alert.createdAt, known + added)
        if (entry == existing) return
        _entries.value = (current.filter { it.id != alert.id } + entry).sortedByDescending { it.createdAt }
        save()
    }

    fun record(alerts: List<SafetyAlert>) = alerts.forEach(::record)

    /** "Clear history": forgets every entry and removes the file. */
    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        file?.delete()
    }

    private fun save() {
        val file = file ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(HotMessApi.json.encodeToString(SERIALIZER, _entries.value))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun read(): List<Entry> {
        val file = file?.takeIf { it.exists() } ?: return emptyList()
        return runCatching { HotMessApi.json.decodeFromString(SERIALIZER, file.readText()) }.getOrDefault(emptyList())
    }

    private companion object {
        val SERIALIZER = ListSerializer(Entry.serializer())
    }
}
