package com.autobirthday

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val messageTemplate: String = "Happy Birthday! 🎉🎂",
    val sendTimeHour: Int = 9,
    val sendTimeMinute: Int = 0
)
