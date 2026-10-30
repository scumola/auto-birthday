package com.autobirthday

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
        private val dateTile: View = itemView.findViewById(R.id.dateTile)
        private val monthTextView: TextView = itemView.findViewById(R.id.monthTextView)
        private val dayTextView: TextView = itemView.findViewById(R.id.dayTextView)
        private val nameTextView: TextView = itemView.findViewById(R.id.nameTextView)
        private val whenTextView: TextView = itemView.findViewById(R.id.whenTextView)
        private val phoneTextView: TextView = itemView.findViewById(R.id.phoneTextView)
        private val enabledSwitch: MaterialSwitch = itemView.findViewById(R.id.enabledSwitch)

        fun bind(contact: ContactEntity, onToggle: (ContactEntity, Boolean) -> Unit) {
            val today = LocalDate.now()
            val next = BirthdayDates.nextOccurrence(contact.birthdayDate, today)
            val isToday = next == today

            nameTextView.text = contact.name
            phoneTextView.text = contact.phoneNumber
            monthTextView.text = next?.format(MONTH) ?: ""
            dayTextView.text = next?.dayOfMonth?.toString() ?: "?"
            whenTextView.text = if (next != null) {
                BirthdayDates.relativeLabel(BirthdayDates.daysUntil(contact.birthdayDate, today))
            } else {
                contact.birthdayDate
            }

            // Tile style carries the state at a glance: today beats on/off
            val (tileBackground, tileTextAttr) = when {
                isToday -> R.drawable.bg_date_tile_today to com.google.android.material.R.attr.colorOnTertiary
                contact.enabled -> R.drawable.bg_date_tile_on to com.google.android.material.R.attr.colorOnPrimaryContainer
                else -> R.drawable.bg_date_tile_off to com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            dateTile.setBackgroundResource(tileBackground)
            val tileTextColor = MaterialColors.getColor(itemView, tileTextAttr)
            monthTextView.setTextColor(tileTextColor)
            dayTextView.setTextColor(tileTextColor)

            nameTextView.setTextColor(
                MaterialColors.getColor(
                    itemView,
                    if (contact.enabled) com.google.android.material.R.attr.colorOnSurface
                    else com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            whenTextView.setTextColor(
                MaterialColors.getColor(
                    itemView,
                    if (isToday) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )

            enabledSwitch.setOnCheckedChangeListener(null)
            enabledSwitch.isChecked = contact.enabled

            enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(contact, isChecked)
            }
            // Whole row is a tap target for the switch
            itemView.setOnClickListener { enabledSwitch.toggle() }
        }

        companion object {
            private val MONTH = DateTimeFormatter.ofPattern("MMM")
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
