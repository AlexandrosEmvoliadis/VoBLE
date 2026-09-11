package com.example.neuralaudionode

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One conversation. The peer is fixed (from the tapped contact); no picker, no per-msg QR. */
class ChatActivity : ComponentActivity() {

    private val engine by lazy { (application as App).engine }
    private lateinit var peerHex: String
    private lateinit var chatList: RecyclerView
    private lateinit var adapter: ChatAdapter
    private val items = mutableListOf<Message>()
    private lateinit var recordButton: Button
    private lateinit var statusText: TextView

    private var recorder: Recorder? = null
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        peerHex = intent.getStringExtra("peer") ?: run { finish(); return }

        findViewById<TextView>(R.id.chatTitle).text =
            engine.contacts.name(peerHex) ?: peerHex.take(6)
        statusText = findViewById(R.id.chatStatus)
        chatList = findViewById(R.id.chatMessages)
        adapter = ChatAdapter(items)
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = adapter

        recordButton = findViewById(R.id.recordButton)
        recordButton.setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.sendButton).setOnClickListener { send() }

        loadThread()
    }

    override fun onResume() {
        super.onResume()
        // live-append messages that arrive for THIS peer while the chat is open
        engine.messageListener = { p, m -> if (p == peerHex) runOnUiThread { adapter.append(m); scrollDown() } }
    }

    private fun loadThread() {
        items.clear(); items.addAll(engine.messages(peerHex))
        adapter.notifyDataSetChanged(); scrollDown()
    }

    private var lastAudio: FloatArray? = null
    private fun toggleRecord() {
        if (!recording) {
            recorder = engine.recordSession().also { it.start() }
            recording = true; recordButton.text = "■ STOP"; status("Recording…")
        } else {
            lastAudio = recorder?.stop(); recording = false; recordButton.text = "● REC"; status("Ready to send")
        }
    }

    private fun send() {
        val audio = lastAudio ?: run { Toast.makeText(this, "Record first", Toast.LENGTH_SHORT).show(); return }
        engine.sendTo(peerHex, audio) { s -> runOnUiThread { status(s); loadThread() } }
    }

    private fun status(s: String) { statusText.text = s }
    private fun scrollDown() { if (items.isNotEmpty()) chatList.scrollToPosition(items.size - 1) }
}
