package com.autobirthday

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val contactId: String,
    val name: String,
    val phoneNumber: String,
    val birthdayDate: String, // Format: MM-DD
    val enabled: Boolean = false
)
