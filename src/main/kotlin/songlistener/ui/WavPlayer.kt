package songlistener.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/** Plays one recording at a time through the Mac's speakers. */
object WavPlayer {
    private var clip: Clip? = null
    private val _playing = MutableStateFlow<File?>(null)
    /** The recording playing now, if any. */
    val playing: StateFlow<File?> = _playing

    /** Position of the playing recording, in seconds, and its length. */
    fun position(): Pair<Double, Double>? = clip?.let { it.microsecondPosition / 1e6 to it.microsecondLength / 1e6 }

    @Synchronized
    fun play(file: File, fromSeconds: Double = 0.0) {
        stop()
        runCatching {
            val c = AudioSystem.getClip()
            c.open(AudioSystem.getAudioInputStream(file))
            c.microsecondPosition = (fromSeconds * 1e6).toLong()
            c.addLineListener { e -> if (e.type == LineEvent.Type.STOP && clip === c && !c.isRunning) stop() }
            c.start()
            clip = c
            _playing.value = file
        }
    }

    @Synchronized
    fun stop() {
        clip?.let { runCatching { it.stop(); it.close() } }
        clip = null
        _playing.value = null
    }
}
