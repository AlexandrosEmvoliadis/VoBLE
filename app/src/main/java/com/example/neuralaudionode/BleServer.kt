package com.example.neuralaudionode

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.*

/**
 * Peripheral half of a dual-role BLE node — the Android mirror of ble_server.py.
 *
 * It advertises the same NUS service the phone's client half already writes to, runs a GATT
 * server, and reassembles the SAME wire framing the Pi expects: a 4-byte big-endian length
 * header followed by that many payload bytes, streamed across many writes. When a full message
 * is reassembled it fires onMessage(bytes) on a worker — exactly what the Pi's write_callback
 * does before handing to its decode queue.
 *
 * NOTIFY plumbing is included now (subscriber tracking + push()) because the relay step needs
 * it: to forward a message to a destination phone, the relay writes it out as a notification to
 * a central that subscribed. For plain phone<->phone receive you can ignore push().
 */
class BleServer(
    private val context: Context,
    private val onMessage: (ByteArray) -> Unit,     // full reassembled payload
    private val log: (String) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_UUID: UUID    = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BleServer"
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val subscribers = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    // reassembly state — mirrors the Pi's buffer / expected_size logic
    private val buffer = ByteArrayOutputStream()
    private var expectedSize: Int = -1
    private val lock = Object()

    @SuppressLint("MissingPermission")
    fun start() {
        gattServer = btManager.openGattServer(context, serverCallback)

        val char = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        char.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        notifyChar = char

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(char)
        gattServer?.addService(service)

        advertiser = btManager.adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            advertiser?.startAdvertising(settings, data, scanResponse, advCallback)
        log("Server up + advertising (${btManager.adapter.name})")
    }
    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            log("ADVERTISING OK")
        }
        override fun onStartFailure(errorCode: Int) { log("Advertise failed: $errorCode") }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            feed(value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (value.isNotEmpty() && value[0].toInt() != 0) subscribers.add(device)
                else subscribers.remove(device)
                log("Subscribers: ${subscribers.size}")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                synchronized(lock) { buffer.reset(); expectedSize = -1 }
            }
        }
    }

    /** Reassemble length-prefixed frames from chunked writes — identical logic to the Pi. */
    private val MAGIC = 0x4E455552

    private fun feed(chunk: ByteArray) {
        synchronized(lock) {
            buffer.write(chunk)
            while (true) {
                var data = buffer.toByteArray()

                // Scan for MAGIC header
                if (expectedSize < 0) {
                    if (data.size < 8) return  // need MAGIC(4) + length(4)

                    // Find MAGIC — slide forward until we find it
                    var magicPos = -1
                    for (i in 0..data.size - 4) {
                        val m = ((data[i].toInt() and 0xFF) shl 24) or
                                ((data[i+1].toInt() and 0xFF) shl 16) or
                                ((data[i+2].toInt() and 0xFF) shl 8) or
                                 (data[i+3].toInt() and 0xFF)
                        if (m == MAGIC) { magicPos = i; break }
                    }
                    if (magicPos < 0) { buffer.reset(); return }  // no MAGIC found, discard
                    if (magicPos + 8 > data.size) return  // partial header

                    expectedSize = ByteBuffer.wrap(data, magicPos + 4, 4).int
                    data = data.copyOfRange(magicPos + 8, data.size)
                    buffer.reset(); buffer.write(data)
                    log("Full message reassembled: ${expectedSize + 8} bytes")
                }

                data = buffer.toByteArray()
                if (data.size < expectedSize) return

                val message = data.copyOfRange(0, expectedSize)
                val rest = data.copyOfRange(expectedSize, data.size)
                buffer.reset(); buffer.write(rest)
                expectedSize = -1
                onMessage(message)
            }
        }
    }

    /** Relay use: push a full framed message to subscribed centrals as notifications. */
    @SuppressLint("MissingPermission")
    fun push(framed: ByteArray, mtuPayload: Int = 244) {
        val char = notifyChar ?: return
        val header = ByteBuffer.allocate(4).putInt(framed.size).array()
        val stream = header + framed
        var i = 0
        while (i < stream.size) {
            val end = minOf(i + mtuPayload, stream.size)
            char.value = stream.copyOfRange(i, end)
            synchronized(subscribers) {
                subscribers.forEach { gattServer?.notifyCharacteristicChanged(it, char, false) }
            }
            Thread.sleep(3)
            i = end
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
    }
}
