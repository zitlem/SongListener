package songlistener.io

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Joins the STT server's `audio_stream` room (raw int16 LE PCM, 16 kHz mono, 1 s per `audio_chunk`,
 * taken before VAD) and follows `audio_level` for the PANNs music probability.
 */
class SttClient {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _musicProb = MutableStateFlow(0f)
    val musicProb: StateFlow<Float> = _musicProb
    private val _audio = MutableSharedFlow<ShortArray>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val audio: SharedFlow<ShortArray> = _audio
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var socket: Socket? = null

    fun start(url: String) {
        stop()
        val opts = IO.Options.builder()
            .setTransports(arrayOf("websocket"))
            .setReconnection(true)
            .setReconnectionAttempts(Int.MAX_VALUE)
            .setReconnectionDelay(2000)
            .build()
        val s = IO.socket(URI.create(url.trim()), opts)
        s.on(Socket.EVENT_CONNECT) {
            _connected.value = true
            _error.value = null
            s.emit("join_audio_stream")
        }
        s.on(Socket.EVENT_DISCONNECT) {
            _connected.value = false
            // No more audio_level events will come; don't leave the last reading standing.
            _musicProb.value = 0f
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            _connected.value = false
            _error.value = args.firstOrNull()?.toString()
        }
        s.on("audio_chunk") { args ->
            val bytes = args.firstOrNull() as? ByteArray ?: return@on
            _audio.tryEmit(pcm16le(bytes))
        }
        s.on("audio_level") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            _musicProb.value = musicProbOf(data)
        }
        socket = s
        s.connect()
    }

    fun stop() {
        socket?.let {
            if (it.connected()) it.emit("leave_audio_stream")
            it.off()
            it.disconnect()
            it.close()
        }
        socket = null
        _connected.value = false
        _musicProb.value = 0f
    }

    internal companion object {
        fun pcm16le(bytes: ByteArray): ShortArray {
            val out = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
            return out
        }

        /** PANNs `music_prob` when the tagger runs; otherwise the energy detector's `audio_type`. */
        fun musicProbOf(data: JSONObject): Float {
            if (!data.isNull("music_prob")) return data.optDouble("music_prob", 0.0).toFloat()
            return if (data.optString("audio_type") == "Music") 1f else 0f
        }
    }
}
