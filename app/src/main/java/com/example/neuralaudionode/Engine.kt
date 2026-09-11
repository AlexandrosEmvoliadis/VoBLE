package com.example.neuralaudionode

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.github.luben.zstd.Zstd
import java.nio.ByteBuffer
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.min

class Engine(private val context: Context) {

    private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val CHAR_UUID    = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val MAGIC = 0x4E455552
    private val sampleRate = 24000
    private val main = Handler(Looper.getMainLooper())

    val identity by lazy { Identity(context) }
    val contacts by lazy { ContactStore(context) }
    private val chat by lazy { ChatStore(context) }
    private val decoder by lazy { AudioDecoder(context) }
    private lateinit var env: OrtEnvironment
    private lateinit var encoder: OrtSession
    private lateinit var server: BleServer

    private val btManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner get() = btManager.adapter.bluetoothLeScanner

    @Volatile var messageListener: ((String, Message) -> Unit)? = null

    fun init() {
        Notifications.ensureChannel(context)
        thread {
            env = OrtEnvironment.getEnvironment()
            encoder = env.createSession(context.assets.open("encodec_encoder_tokenizer_pruned.onnx").readBytes())
            decoder.load()
            Log.d("ENGINE", "models loaded; id ${identity.peerId.toHex()}")
        }
        server = BleServer(context, { msg -> thread { handleIncoming(msg) } }, { s -> Log.d("SRV", s) })
    }

    fun startServer() {
        try { server.start() } catch (e: Exception) { Log.e("ENGINE", "server start: ${e.message}") }
    }

    fun myPublicKeyB64() = identity.publicKeyB64()
    fun myPeerIdHex() = identity.peerId.toHex()
    fun messages(peerHex: String) = chat.messages(peerHex)

    private fun handleIncoming(message: ByteArray) {
        val msg = Wire.parse(message) ?: return
        val srcHex = Identity.peerIdOf(msg.srcPubKey).toHex()
        if (msg.dstPeerId.contentEquals(identity.peerId)) {
            try {
                val opened = identity.open(msg.sealed)
                val audio = decoder.decodeToAudio(opened)
                val stored = chat.add(srcHex, incoming = true, audio)
                if (contacts.byPeerId(srcHex) == null) contacts.add(srcHex.take(6), msg.srcPubKey)
                main.post { messageListener?.invoke(srcHex, stored) }
                Notifications.newMessage(context, contacts.name(srcHex) ?: srcHex.take(6))
            } catch (e: Exception) { Log.e("CRYPTO", "open/decode failed: ${e.message}") }
        } else Log.d("MESH", "for ${msg.dstPeerId.toHex()} — relay not enabled yet")
    }

    fun sendTo(peerHex: String, audio: FloatArray, status: (String) -> Unit) {
        val pub = contacts.pubKey(peerHex) ?: run { status("Unknown contact"); return }
        thread {
            val payload = compressZstd(packVarlen(encode(audio)))
            val framed = Wire.pack(Identity.peerIdOf(pub), identity.publicKey, identity.seal(pub, payload))
            chat.add(peerHex, incoming = false, audio)
            main.post { messageListener?.invoke(peerHex, chat.messages(peerHex).last()) }
            scanConnectWrite(framed, status)
        }
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var pending: ByteArray? = null
    private var statusCb: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    private fun scanConnectWrite(framed: ByteArray, status: (String) -> Unit) {
        pending = framed; statusCb = status; writeChar = null
        var connected = false
        status("Finding peer…")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                scanner.stopScan(this)
                connected = true
                status("Connecting…")
                gatt = r.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        scanner.startScan(listOf(filter), settings, cb)
        main.postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            if (!connected) statusCb?.invoke("No peer nearby")
        }, 8000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, ns: Int) {
            if (ns == BluetoothProfile.STATE_CONNECTED) g.requestMtu(517)
            else if (ns == BluetoothProfile.STATE_DISCONNECTED) { writeChar = null }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, s: Int) { main.postDelayed({ g.discoverServices() }, 800) }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            val svc = g.getService(SERVICE_UUID) ?: run { main.postDelayed({ g.discoverServices() }, 1000); return }
            writeChar = svc.getCharacteristic(CHAR_UUID)
            pending?.let { thread { write(it) } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun write(data: ByteArray) {
        val g = gatt ?: return; val c = writeChar ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        fun put(b: ByteArray) { c.value = b; g.writeCharacteristic(c) }
        val header = ByteBuffer.allocate(8).putInt(MAGIC).putInt(data.size).array()
        put(header); Thread.sleep(30)
        var i = 0
        while (i < data.size) { val e = min(i + 180, data.size); put(data.copyOfRange(i, e)); Thread.sleep(20); i = e }
        statusCb?.invoke("Sent (encrypted)")
        pending = null
        Thread.sleep(200)
        try { g.disconnect(); g.close() } catch (_: Exception) {}
        gatt = null; writeChar = null
    }

    private fun encode(audio: FloatArray): ShortArray {
        val t = OnnxTensor.createTensor(env, arrayOf(arrayOf(audio)))
        val out = encoder.run(mapOf("audio" to t))[0] as OnnxTensor
        @Suppress("UNCHECKED_CAST") val codes = out.value as Array<Array<LongArray>>
        val cb = codes.size; val fr = codes[0][0].size; val tok = ShortArray(fr * cb)
        for (f in 0 until fr) for (q in 0 until cb) tok[f * cb + q] = codes[q][0][f].toShort()
        t.close(); out.close(); return tok
    }
    private fun packVarlen(tokens: ShortArray): ByteArray {
        val out = ArrayList<Byte>()
        for (tk in tokens) { var v = tk.toInt(); while (v >= 255) { out.add(255.toByte()); v -= 255 }; out.add(v.toByte()) }
        return out.toByteArray()
    }
    private fun compressZstd(data: ByteArray): ByteArray {
        val dst = ByteArray(Zstd.compressBound(data.size.toLong()).toInt())
        val n = Zstd.compress(dst, data, 3); if (Zstd.isError(n)) throw RuntimeException(Zstd.getErrorName(n))
        return dst.copyOf(n.toInt())
    }

    fun recordSession(): Recorder = Recorder(sampleRate)
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
