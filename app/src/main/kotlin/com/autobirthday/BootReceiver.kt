package com.autobirthday

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted, rescheduling alarm")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AlarmScheduler.scheduleNextAlarm(context)
                    Log.d("BootReceiver", "Alarm rescheduled after boot")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to reschedule alarm: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
