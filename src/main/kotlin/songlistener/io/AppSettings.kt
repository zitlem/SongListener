package songlistener.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppSettings(
    val churchPresenterUrl: String = "http://localhost:8765",
    val apiKey: String = "",
    val sttUrl: String = "http://localhost:80",
    /**
     * This SongListener's id towards ChurchPresenter, made once and kept: ChurchPresenter decides
     * per device whether to ask the operator before a "Show it", so it must not change per launch.
     */
    val deviceId: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun load(file: File): AppSettings =
            runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrDefault(AppSettings())

        /** The kept device id, made and saved on first use. */
        fun deviceId(file: File): String {
            val s = load(file)
            if (s.deviceId.isNotBlank()) return s.deviceId
            val id = "songlistener-" + java.util.UUID.randomUUID()
            save(file, s.copy(deviceId = id))
            return id
        }

        fun save(file: File, settings: AppSettings) {
            file.parentFile.mkdirs()
            file.writeText(json.encodeToString(serializer(), settings))
        }
    }
}
