package com.example.neuralaudionode

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.*
import android.util.Log
import com.github.luben.zstd.Zstd

/**
 * On-device decode — the Kotlin mirror of ble_server.py's process_message / decode_audio /
 * unpack_varlen. Takes the reassembled payload (zstd(varlen(tokens))) and plays audio.
 *
 *   zstd-decompress  ->  unpack_varlen  ->  reshape to (codebooks,1,frames)  ->  decoder ONNX  ->  play
 *
 * CODEBOOKS must equal what the encoder emits. The Pi hardcodes 4 (3 kbps EnCodec). Your phone
 * reads it dynamically in encodeAudio() as codes.size — log that once and confirm it's 4, or set
 * CODEBOOKS to match, otherwise the reshape scrambles tokens into noise.
 */
class AudioDecoder(private val context: Context) {
    companion object { private const val CODEBOOKS = 4; private const val SR = 24000 }

    private lateinit var env: OrtEnvironment
    private lateinit var decoder: OrtSession

    fun load() {
        env = OrtEnvironment.getEnvironment()
        decoder = env.createSession(
            context.assets.open("encodec_decoder_reconstruct_pruned.onnx").readBytes()
        )
        Log.d("AudioDecoder", "decoder loaded; inputs=${decoder.inputNames}")
    }

    /** Full pipeline on one reassembled message. */
    fun handle(payload: ByteArray) {
        val decompressed = zstdDecompress(payload)
        val tokens = unpackVarlen(decompressed)
        val audio = decode(tokens)
        play(audio)
    }
    fun decodeToAudio(payload: ByteArray): FloatArray {
        val decompressed = zstdDecompress(payload)
        val tokens = unpackVarlen(decompressed)
        return decode(tokens)
    }
    private fun zstdDecompress(data: ByteArray): ByteArray {
        val size = Zstd.getFrameContentSize(data)                 // embedded by one-shot compress
        val out = ByteArray(size.toInt())
        val n = Zstd.decompress(out, data)
        if (Zstd.isError(n)) throw RuntimeException(Zstd.getErrorName(n))
        return out.copyOf(n.toInt())
    }

    /** Exact mirror of the Pi's unpack_varlen. */
    private fun unpackVarlen(data: ByteArray): IntArray {
        val tokens = ArrayList<Int>()
        var acc = 0
        for (byteVal in data) {
            val b = byteVal.toInt() and 0xFF
            if (b == 255) acc += 255
            else { tokens.add(acc + b); acc = 0 }
        }
        if (acc > 0) tokens.add(acc)
        return tokens.toIntArray()
    }

    /** tokens are frame-major (t[f*cb+q]); decoder wants codes[q][0][f] -> shape (cb,1,frames). */
    private fun decode(tokensFlat: IntArray): FloatArray {
        val frames = tokensFlat.size / CODEBOOKS
        val codes = Array(CODEBOOKS) { q ->
            arrayOf(LongArray(frames) { f -> tokensFlat[f * CODEBOOKS + q].toLong() })
        }
        val tensor = OnnxTensor.createTensor(env, codes)
        val out = decoder.run(mapOf("codes" to tensor))[0] as OnnxTensor
        // decoder output is audio; squeeze whatever leading dims to a flat FloatArray
        @Suppress("UNCHECKED_CAST")
        val audio = when (val v = out.value) {
            is Array<*> -> flatten(v)
            else -> throw RuntimeException("unexpected decoder output: ${v?.javaClass}")
        }
        tensor.close(); out.close()
        val peak = audio.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        if (peak > 0f) for (i in audio.indices) audio[i] /= peak
        return audio
    }

    private fun flatten(a: Array<*>): FloatArray {
        val acc = ArrayList<Float>()
        fun rec(x: Any?) {
            when (x) {
                is FloatArray -> x.forEach { acc.add(it) }
                is Array<*> -> x.forEach { rec(it) }
            }
        }
        rec(a)
        return acc.toFloatArray()
    }

    private fun play(audio: FloatArray) {
        val pcm = ShortArray(audio.size) { (audio[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val track = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
            AudioFormat.Builder().setSampleRate(SR)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            (pcm.size * 2).coerceAtLeast(4), AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.write(pcm, 0, pcm.size)
        track.play()
    }
}
