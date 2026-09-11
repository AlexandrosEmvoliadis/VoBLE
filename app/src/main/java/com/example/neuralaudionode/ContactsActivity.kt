package com.example.neuralaudionode

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class ContactsActivity : ComponentActivity() {

    private val engine by lazy { (application as App).engine }
    private lateinit var list: RecyclerView
    private lateinit var adapter: ContactsAdapter
    private val items = mutableListOf<Contact>()

    private val qrScanner = registerForActivityResult(ScanContract()) { r ->
        val peer = r.contents?.let { QrHelper.parse(it) } ?: return@registerForActivityResult
        val input = EditText(this).apply { hint = "Contact name" }
        AlertDialog.Builder(this).setTitle("Save contact")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { peer.peerId.joinToString("") { b -> "%02x".format(b) }.take(6) }
                engine.contacts.add(name, peer.pubKey)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        list = findViewById(R.id.contactsList)
        adapter = ContactsAdapter(items) { c ->
            startActivity(Intent(this, ChatActivity::class.java).putExtra("peer", c.peerIdHex))
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.addContactButton).setOnClickListener {
            qrScanner.launch(ScanOptions().setPrompt("Scan contact's QR").setBeepEnabled(false))
        }
        findViewById<Button>(R.id.myQrButton).setOnClickListener { showMyQr() }

        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS
        ), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) BleService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        engine.messageListener = { _, _ -> runOnUiThread { refresh() } }
    }

    private fun refresh() {
        items.clear(); items.addAll(engine.contacts.all())
        adapter.notifyDataSetChanged()
    }

    private fun showMyQr() {
        val img = ImageView(this).apply {
            setImageBitmap(QrHelper.encode(engine.myPublicKeyB64()))
            val p = (16 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this).setTitle("My QR (id ${engine.myPeerIdHex().take(6)})")
            .setView(img).setPositiveButton("Done", null).show()
    }
}
