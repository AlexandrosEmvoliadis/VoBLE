package com.example.neuralaudionode

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.json.JSONObject
import java.nio.ByteBuffer

object Wire {
    const val VERSION = 1
    const val HEADER_LEN = 1 + 8 + 32

    fun pack(dstPeerId: ByteArray, srcPubKey: ByteArray, sealed: ByteArray): ByteArray {
        require(dstPeerId.size == 8 && srcPubKey.size == 32)
        return ByteBuffer.allocate(HEADER_LEN + sealed.size)
            .put(VERSION.toByte()).put(dstPeerId).put(srcPubKey).put(sealed)
            .array()
    }

    data class Msg(val dstPeerId: ByteArray, val srcPubKey: ByteArray, val sealed: ByteArray)

    fun parse(message: ByteArray): Msg? {
        if (message.size < HEADER_LEN) return null
        return Msg(message.copyOfRange(1, 9), message.copyOfRange(9, 41), message.copyOfRange(41, message.size))
    }
}

object QrHelper {
    data class Peer(val peerId: ByteArray, val pubKey: ByteArray)

    fun encode(pubKeyB64: String): Bitmap {
        val payload = JSONObject().put("pk", pubKeyB64).toString()
        return BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 640, 640)
    }

    fun parse(scanned: String): Peer? = try {
        val pk = Base64.decode(JSONObject(scanned).getString("pk"), Base64.NO_WRAP)
        Peer(Identity.peerIdOf(pk), pk)
    } catch (_: Exception) { null }
}
