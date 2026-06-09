package com.autobirthday

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var contactAdapter: ContactAdapter
    private lateinit var messageEditText: EditText
    private lateinit var timeTextView: TextView
    private lateinit var changeTimeButton: Button
    private lateinit var rescanButton: Button
    private lateinit var contactsRecyclerView: RecyclerView

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            onPermissionsGranted()
        } else {
            Toast.makeText(this, "Permissions are required for the app to work", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(this)

        messageEditText = findViewById(R.id.messageEditText)
        timeTextView = findViewById(R.id.timeTextView)
        changeTimeButton = findViewById(R.id.changeTimeButton)
        rescanButton = findViewById(R.id.rescanButton)
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView)

        setupRecyclerView()
        setupListeners()
        loadSettings()
        observeContacts()

        checkPermissions()

        // Side-load self-update: prompt if a newer APK is published on badcheese.com.
        UpdateChecker.check(this)
    }

    private fun setupRecyclerView() {
        contactAdapter = ContactAdapter { contact, isEnabled ->
            onContactToggled(contact, isEnabled)
        }
        contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactAdapter
        }
    }

    private fun setupListeners() {
        changeTimeButton.setOnClickListener {
            showTimePicker()
        }

        rescanButton.setOnClickListener {
            rescanContacts()
        }

        messageEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveMessageTemplate()
            }
        }
    }

    private fun loadSettings() {
        database.settingsDao().getSettings().observe(this) { settings ->
            if (settings != null) {
                messageEditText.setText(settings.messageTemplate)
                timeTextView.text = String.format("%02d:%02d", settings.sendTimeHour, settings.sendTimeMinute)
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    database.settingsDao().insertSettings(AppSettings())
                }
            }
        }
    }

    private fun observeContacts() {
        database.contactDao().getAllContacts().observe(this) { contacts ->
            contactAdapter.submitList(contacts)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        checkExactAlarmPermission()
        loadContactsIfEmpty()
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("Exact Alarm Permission")
                    .setMessage("This app needs permission to schedule exact alarms to send birthday messages on time.")
                    .setPositiveButton("Grant") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                scheduleAlarm()
            }
        } else {
            scheduleAlarm()
        }
    }

    private fun loadContactsIfEmpty() {
        lifecycleScope.launch {
            // Check for ANY contacts, not just enabled ones — we only want to auto-scan
            // on first launch (truly empty DB). Using getEnabledContacts() would trigger
            // a rescan whenever all contacts happen to be disabled, wiping their saved states.
            val contacts = withContext(Dispatchers.IO) {
                database.contactDao().getAllContactsSync()
            }
            if (contacts.isEmpty()) {
                rescanContacts()
            }
        }
    }

    private fun rescanContacts() {
        rescanButton.isEnabled = false
        rescanButton.text = "Scanning..."

        lifecycleScope.launch {
            try {
                // Get existing contacts and their enabled states
                val existingContacts = withContext(Dispatchers.IO) {
                    database.contactDao().getAllContactsSync()
                }
                val existingStates = existingContacts.associate { it.contactId to it.enabled }

                val scanner = ContactScanner(this@MainActivity)
                val scannedContacts = scanner.scanContacts()

                // Preserve enabled state for existing contacts, use false for new ones
                val contactsToInsert = scannedContacts.map { contact ->
                    val previousState = existingStates[contact.contactId]
                    if (previousState != null) {
                        contact.copy(enabled = previousState)
                    } else {
                        contact // Already has enabled = false from scanner
                    }
                }

                withContext(Dispatchers.IO) {
                    database.contactDao().deleteAll()
                    database.contactDao().insertAll(contactsToInsert)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Found ${contactsToInsert.size} contacts with birthdays",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error scanning contacts: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    rescanButton.isEnabled = true
                    rescanButton.text = "Rescan Contacts"
                }
            }
        }
    }

    private fun showTimePicker() {
        lifecycleScope.launch {
            val settings = withContext(Dispatchers.IO) {
                database.settingsDao().getSettingsSync() ?: AppSettings()
            }

            TimePickerDialog(
                this@MainActivity,
                { _, hourOfDay, minute ->
                    updateSendTime(hourOfDay, minute)
                },
                settings.sendTimeHour,
                settings.sendTimeMinute,
                true
            ).show()
        }
    }

    private fun updateSendTime(hour: Int, minute: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = database.settingsDao().getSettingsSync() ?: AppSettings()
            val updatedSettings = settings.copy(sendTimeHour = hour, sendTimeMinute = minute)
            database.settingsDao().updateSettings(updatedSettings)

            withContext(Dispatchers.Main) {
                timeTextView.text = String.format("%02d:%02d", hour, minute)
                scheduleAlarm()
            }
        }
    }

    private fun saveMessageTemplate() {
        val message = messageEditText.text.toString()
        if (message.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val settings = database.settingsDao().getSettingsSync() ?: AppSettings()
                val updatedSettings = settings.copy(messageTemplate = message)
                database.settingsDao().updateSettings(updatedSettings)
            }
        }
    }

    private fun onContactToggled(contact: ContactEntity, isEnabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val updatedContact = contact.copy(enabled = isEnabled)
            database.contactDao().update(updatedContact)
        }
    }

    private fun scheduleAlarm() {
        lifecycleScope.launch {
            AlarmScheduler.scheduleNextAlarm(this@MainActivity)
            Toast.makeText(this@MainActivity, "Alarm scheduled", Toast.LENGTH_SHORT).show()
        }
    }
}
