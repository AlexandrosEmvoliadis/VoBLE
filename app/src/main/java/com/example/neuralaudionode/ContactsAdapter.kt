package com.example.neuralaudionode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Rows in the contacts/conversations list. Tap a row -> open that chat. */
class ContactsAdapter(
    private val items: List<Contact>,
    private val onClick: (Contact) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.name.text = c.name
        holder.sub.text = "id ${c.peerIdHex.take(6)}"
        holder.itemView.setOnClickListener { onClick(c) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.contactName)
        val sub: TextView = v.findViewById(R.id.contactSub)
    }
}
