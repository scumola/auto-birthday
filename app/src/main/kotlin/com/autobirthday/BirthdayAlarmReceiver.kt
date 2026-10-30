package com.autobirthday

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class BirthdayAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BirthdayAlarm", "Alarm received")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                checkAndSendBirthdayMessages(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun checkAndSendBirthdayMessages(context: Context) {
        val database = AppDatabase.getDatabase(context)
        val contactDao = database.contactDao()
        val settingsDao = database.settingsDao()

        val todayDate = getTodayDateString()
        val birthdayContacts = contactDao.getTodaysBirthdays(todayDate)

        if (birthdayContacts.isEmpty()) {
            Log.d("BirthdayAlarm", "No birthdays today ($todayDate)")
        } else {
            val settings = settingsDao.getSettingsSync() ?: AppSettings()
            val message = settings.messageTemplate

            val smsManager = context.getSystemService(SmsManager::class.java)

            // sendTextMessage only handles a single SMS: 160 GSM chars, or just 70 once the text
            // contains an emoji (UCS-2), and longer messages fail without an error. Split it and
            // send as multipart; a short message comes back as one part and goes out as one SMS.
            val parts = smsManager.divideMessage(message)

            for (contact in birthdayContacts) {
                try {
                    smsManager.sendMultipartTextMessage(
                        contact.phoneNumber,
                        null,
                        parts,
                        null,
                        null
                    )
                    Log.d("BirthdayAlarm", "Sent birthday SMS to ${contact.name}")
                } catch (e: Exception) {
                    Log.e("BirthdayAlarm", "Failed to send SMS to ${contact.name}: ${e.message}")
                }
            }
        }

        // Always reschedule for tomorrow
        AlarmScheduler.scheduleNextAlarm(context)
    }

    private fun getTodayDateString(): String {
        val calendar = Calendar.getInstance()
        val formatter = SimpleDateFormat("MM-dd", Locale.US)
        return formatter.format(calendar.time)
    }
}
