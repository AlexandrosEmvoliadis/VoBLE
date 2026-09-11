package com.example.neuralaudionode

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

/** Messenger-style thread: outgoing bubbles right, incoming left, each with a ▶ play button. */
class ChatAdapter(private val items: MutableList<Message>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var player: MediaPlayer? = null

    override fun getItemViewType(position: Int) = if (items[position].incoming) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 0) R.layout.item_msg_in else R.layout.item_msg_out
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.time.text = fmt.format(Date(m.timestamp))
        holder.play.text = "▶ ${m.durationMs / 1000f}s".let { "▶ %.1fs".format(m.durationMs / 1000f) }
        holder.play.setOnClickListener {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(m.wavPath); setOnPreparedListener { start() }; prepareAsync()
            }
        }
    }

    override fun getItemCount() = items.size

    fun append(m: Message) { items.add(m); notifyItemInserted(items.size - 1) }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val play: Button = v.findViewById(R.id.playButton)
        val time: TextView = v.findViewById(R.id.timeText)
    }
}
