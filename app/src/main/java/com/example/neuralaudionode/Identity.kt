package com.example.neuralaudionode

import android.content.Context
import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair
import java.security.MessageDigest

/**
 * Persistent cryptographic identity for a BLEChat node.
 *
 * - One X25519 keypair, generated on first launch and saved, so this device keeps a STABLE
 *   peer_id across restarts (required for store-and-forward: a message parked on a relay must
 *   still be decryptable after the recipient reboots).
 * - seal(recipientPub, data) -> sealed box that ONLY the holder of recipientPub's private key
 *   can open. A relay forwarding these bytes cannot read them — that's the whole point.
 * - open(sealed) -> plaintext, only if the box was sealed to OUR public key; throws otherwise.
 *
 * peer_id = first 8 bytes of SHA-256(publicKey) — a public, one-way address for routing. It
 * cannot decrypt anything; confidentiality comes from the keypair, addressing from the hash.
 *
 * PoC note: keys are stored in app-private SharedPreferences. For production move the private
 * key into Android Keystore / EncryptedSharedPreferences so a rooted device can't lift it.
 */
class Identity(context: Context) {
    private val ls = LazySodiumAndroid(SodiumAndroid())
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val keyPair: KeyPair

    init {
        val pk = prefs.getString("pk", null)
        val sk = prefs.getString("sk", null)
        keyPair = if (pk != null && sk != null) {
            KeyPair(Key.fromBytes(b64d(pk)), Key.fromBytes(b64d(sk)))
        } else {
            val kp = (ls as Box.Lazy).cryptoBoxKeypair()
            prefs.edit()
                .putString("pk", b64e(kp.publicKey.asBytes))
                .putString("sk", b64e(kp.secretKey.asBytes))
                .apply()
            kp
        }
    }

    val publicKey: ByteArray get() = keyPair.publicKey.asBytes
    fun publicKeyB64(): String = b64e(publicKey)
    val peerId: ByteArray get() = peerIdOf(publicKey)

    /** Encrypt for a recipient whose public key we scanned (or learned from a message header). */
    fun seal(recipientPub: ByteArray, plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + Box.SEALBYTES)
        check(ls.cryptoBoxSeal(out, plaintext, plaintext.size.toLong(), recipientPub)) { "seal failed" }
        return out
    }

    /** Open a box sealed to us. Throws if it wasn't (e.g. we're only a relay). */
    fun open(sealed: ByteArray): ByteArray {
        val out = ByteArray(sealed.size - Box.SEALBYTES)
        check(
            ls.cryptoBoxSealOpen(out, sealed, sealed.size.toLong(), publicKey, keyPair.secretKey.asBytes)
        ) { "open failed — not addressed to us" }
        return out
    }

    companion object {
        fun peerIdOf(pub: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(pub).copyOf(8)
        private fun b64e(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)
    }
}
