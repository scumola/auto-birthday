package com.autobirthday

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private val onContactToggle: (ContactEntity, Boolean) -> Unit
) : ListAdapter<ContactEntity, ContactAdapter.ContactViewHolder>(ContactDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = getItem(position)
        holder.bind(contact, onContactToggle)
    }

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.nameTextView)
        private val birthdayTextView: TextView = itemView.findViewById(R.id.birthdayTextView)
        private val phoneTextView: TextView = itemView.findViewById(R.id.phoneTextView)
        private val enabledSwitch: SwitchCompat = itemView.findViewById(R.id.enabledSwitch)

        fun bind(contact: ContactEntity, onToggle: (ContactEntity, Boolean) -> Unit) {
            nameTextView.text = contact.name
            birthdayTextView.text = "Birthday: ${contact.birthdayDate}"
            phoneTextView.text = contact.phoneNumber

            enabledSwitch.setOnCheckedChangeListener(null)
            enabledSwitch.isChecked = contact.enabled

            enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(contact, isChecked)
            }
        }
    }

    class ContactDiffCallback : DiffUtil.ItemCallback<ContactEntity>() {
        override fun areItemsTheSame(oldItem: ContactEntity, newItem: ContactEntity): Boolean {
            return oldItem.contactId == newItem.contactId
        }

        override fun areContentsTheSame(oldItem: ContactEntity, newItem: ContactEntity): Boolean {
            return oldItem == newItem
        }
    }
}
