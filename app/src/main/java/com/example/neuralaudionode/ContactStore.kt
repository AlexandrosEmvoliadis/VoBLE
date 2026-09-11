package com.example.neuralaudionode

import android.content.Context
import android.media.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import android.util.Base64

/** A saved contact: a name + their public key (peerId derived from it). */
data class Contact(val name: String, val peerIdHex: String, val pubKey: ByteArray)

/** Persists named contacts as JSON in filesDir/contacts.json. Scan-a-QR-once then save. */
class ContactStore(context: Context) {
    private val file = File(context.filesDir, "contacts.json")

    fun add(name: String, pubKey: ByteArray) {
        val hex = peerIdHex(pubKey)
        val arr = load()
        // replace if this peer already exists, else append
        var replaced = false
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("peer") == hex) {
                arr.getJSONObject(i).put("name", name); replaced = true; break
            }
        }
        if (!replaced) arr.put(JSONObject().apply {
            put("name", name); put("peer", hex)
            put("pk", Base64.encodeToString(pubKey, Base64.NO_WRAP))
        })
        file.writeText(arr.toString())
    }

    fun all(): List<Contact> {
        val arr = load()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Contact(o.getString("name"), o.getString("peer"),
                Base64.decode(o.getString("pk"), Base64.NO_WRAP))
        }
    }

    fun byPeerId(hex: String): Contact? = all().firstOrNull { it.peerIdHex == hex }
    fun pubKey(hex: String): ByteArray? = byPeerId(hex)?.pubKey
    fun name(hex: String): String? = byPeerId(hex)?.name

    private fun load(): JSONArray =
        file.takeIf { it.exists() }?.let { JSONArray(it.readText()) } ?: JSONArray()

    private fun peerIdHex(pub: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(pub).copyOf(8).joinToString("") { "%02x".format(it) }
}

/** Tiny press-to-talk mic recorder: start(), then stop() returns the float audio. */
class Recorder(private val sampleRate: Int) {
    private var isRecording = false
    private var recorder: AudioRecord? = null
    private val samples = ArrayList<Float>()

    @Suppress("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
        recorder!!.startRecording(); isRecording = true; samples.clear()
        Thread {
            val b = ShortArray(minBuf)
            while (isRecording) { val n = recorder!!.read(b, 0, b.size); for (i in 0 until n) samples.add(b[i] / 32768f) }
        }.start()
    }

    fun stop(): FloatArray {
        isRecording = false
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
        recorder = null
        return samples.toFloatArray()
    }
}
