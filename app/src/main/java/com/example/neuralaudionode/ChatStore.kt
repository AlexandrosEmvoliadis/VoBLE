package com.example.neuralaudionode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One stored voice message. audio is kept on disk as a WAV so the chat survives restarts. */
data class Message(
    val id: String,
    val peerId: String,     // hex peer_id of the other party (thread key)
    val incoming: Boolean,
    val timestamp: Long,
    val wavPath: String,
    val durationMs: Long
)

/**
 * Dead-simple persistent chat store: one folder per peer under filesDir/chats/<peerHex>/,
 * each message's audio saved as a .wav, with an index.json listing them in order. No database
 * dependency (keeps Gradle simple); swap for Room later if the message volume grows.
 */
class ChatStore(private val context: Context) {
    private val root = File(context.filesDir, "chats").apply { mkdirs() }
    private val sr = 24000

    private fun peerDir(peerHex: String) = File(root, peerHex).apply { mkdirs() }
    private fun indexFile(peerHex: String) = File(peerDir(peerHex), "index.json")

    /** Persist a decoded message (audio as float [-1,1]) and return the Message. */
    fun add(peerHex: String, incoming: Boolean, audio: FloatArray): Message {
        val id = System.currentTimeMillis().toString() + "_" + (0..9999).random()
        val wav = File(peerDir(peerHex), "$id.wav")
        writeWav(wav, audio)
        val msg = Message(id, peerHex, incoming, System.currentTimeMillis(),
            wav.absolutePath, (audio.size * 1000L) / sr)
        val arr = loadJson(peerHex)
        arr.put(JSONObject().apply {
            put("id", id); put("in", incoming); put("ts", msg.timestamp)
            put("wav", wav.absolutePath); put("dur", msg.durationMs)
        })
        indexFile(peerHex).writeText(arr.toString())
        return msg
    }

    fun messages(peerHex: String): List<Message> {
        val arr = loadJson(peerHex)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Message(o.getString("id"), peerHex, o.getBoolean("in"),
                o.getLong("ts"), o.getString("wav"), o.getLong("dur"))
        }
    }

    fun peers(): List<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    private fun loadJson(peerHex: String): JSONArray =
        indexFile(peerHex).takeIf { it.exists() }?.let { JSONArray(it.readText()) } ?: JSONArray()

    /** 24 kHz mono 16-bit PCM WAV. */
    private fun writeWav(file: File, audio: FloatArray) {
        val pcm = ByteArray(audio.size * 2)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (f in audio) bb.putShort((f.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val dataLen = pcm.size; val byteRate = sr * 2
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            fun s(str: String) = out.writeBytes(str)
            fun i(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun sh(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            s("RIFF"); i(36 + dataLen); s("WAVE"); s("fmt "); i(16); sh(1); sh(1)
            i(sr); i(byteRate); sh(2); sh(16); s("data"); i(dataLen); out.write(pcm)
        }
    }
}
