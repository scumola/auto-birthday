package com.autobirthday

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var contactAdapter: ContactAdapter
    private lateinit var nextUpLabel: TextView
    private lateinit var nextUpName: TextView
    private lateinit var nextUpDate: TextView
    private lateinit var messageBubble: TextView
    private lateinit var editMessageButton: Button
    private lateinit var changeTimeButton: Button
    private lateinit var enabledCountText: TextView
    private lateinit var rescanButton: Button
    private lateinit var contactsRecyclerView: RecyclerView
    private lateinit var emptyText: TextView

    // Latest values from the two LiveData sources; the next-up section needs both
    private var currentContacts: List<ContactEntity> = emptyList()
    private var currentSettings: AppSettings? = null

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

        nextUpLabel = findViewById(R.id.nextUpLabel)
        nextUpName = findViewById(R.id.nextUpName)
        nextUpDate = findViewById(R.id.nextUpDate)
        messageBubble = findViewById(R.id.messageBubble)
        editMessageButton = findViewById(R.id.editMessageButton)
        changeTimeButton = findViewById(R.id.changeTimeButton)
        enabledCountText = findViewById(R.id.enabledCountText)
        rescanButton = findViewById(R.id.rescanButton)
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView)
        emptyText = findViewById(R.id.emptyText)

        applySystemBarInsets()
        setupRecyclerView()
        setupListeners()
        loadSettings()
        observeContacts()

        checkPermissions()

        // Side-load self-update: prompt if a newer APK is published on badcheese.com.
        UpdateChecker.check(this)
    }

    private fun applySystemBarInsets() {
        // Draw edge-to-edge on every version (Android 15+ forces it anyway). The status bar inset
        // goes on the root, not the app bar: the root clips to its padding, so the header that
        // scrolls away disappears under a solid band instead of showing through the status bar.
        // The list gets the nav bar inset as padding so rows can scroll under the gesture bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val listBottomPadding = contactsRecyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            contactsRecyclerView.updatePadding(bottom = listBottomPadding + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupRecyclerView() {
        contactAdapter = ContactAdapter { contact, isEnabled ->
            onContactToggled(contact, isEnabled)
        }
        contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactAdapter
            // A toggle rebinds the row (tile color changes); skip the cross-fade so it doesn't flicker
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }

    private fun setupListeners() {
        changeTimeButton.setOnClickListener {
            showTimePicker()
        }

        rescanButton.setOnClickListener {
            rescanContacts()
        }

        messageBubble.setOnClickListener { showEditMessageDialog() }
        editMessageButton.setOnClickListener { showEditMessageDialog() }
    }

    private fun loadSettings() {
        database.settingsDao().getSettings().observe(this) { settings ->
            if (settings != null) {
                currentSettings = settings
                messageBubble.text = settings.messageTemplate
                changeTimeButton.text = "Sends at ${formatTime(settings.sendTimeHour, settings.sendTimeMinute)}"
                updateNextUp()
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    database.settingsDao().insertSettings(AppSettings())
                }
            }
        }
    }

    private fun observeContacts() {
        database.contactDao().getAllContacts().observe(this) { contacts ->
            currentContacts = contacts
            // Soonest birthday first; name breaks ties so same-day people stay in a stable order
            val today = LocalDate.now()
            contactAdapter.submitList(
                contacts.sortedWith(
                    compareBy<ContactEntity>({ BirthdayDates.daysUntil(it.birthdayDate, today) }, { it.name })
                )
            )
            enabledCountText.text = if (contacts.isEmpty()) {
                "None found yet"
            } else {
                "${contacts.count { it.enabled }} of ${contacts.size} get a text"
            }
            emptyText.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            updateNextUp()
        }
    }

    private fun updateNextUp() {
        val settings = currentSettings ?: AppSettings()
        val now = LocalDateTime.now()
        val next = BirthdayDates.nextSend(
            currentContacts,
            LocalTime.of(settings.sendTimeHour, settings.sendTimeMinute),
            now
        )

        if (next == null) {
            nextUpLabel.text = "Nothing scheduled"
            nextUpName.text = "Switch on someone below"
            nextUpDate.visibility = View.GONE
            return
        }

        val days = ChronoUnit.DAYS.between(now.toLocalDate(), next.date)
        nextUpLabel.text = "Next up " + BirthdayDates.relativeLabel(days).lowercase()
        nextUpName.text = BirthdayDates.namesLabel(next.contacts)
        // Add the year only when it's far enough out to be ambiguous (e.g. a Feb 29 birthday)
        val pattern = if (days > 300) "EEEE, MMMM d, yyyy" else "EEEE, MMMM d"
        nextUpDate.text = next.date.format(DateTimeFormatter.ofPattern(pattern))
        nextUpDate.visibility = View.VISIBLE
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val pattern = if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm a"
        return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern))
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
                MaterialAlertDialogBuilder(this)
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
        rescanButton.text = "Scanning…"

        lifecycleScope.launch {
            // Order: read saved states -> re-key them to current lookup keys -> scan -> merge ->
            // replace the table. Everything that can fail runs before the single write at the
            // end, so an error mid-rescan leaves the saved states untouched.
            try {
                // Get existing contacts and their enabled states
                val existingContacts = withContext(Dispatchers.IO) {
                    database.contactDao().getAllContactsSync()
                }

                val scanner = ContactScanner(this@MainActivity)

                // Key saved states by each contact's *current* lookup key, not the stored one.
                // Stored keys go stale when a linked account (e.g. Telegram) recreates its raw
                // contacts, and exact-matching them silently un-selected those contacts.
                val existingStates = mutableMapOf<String, Boolean>()
                for (existing in existingContacts) {
                    val currentKey = scanner.resolveCurrentLookupKey(existing.contactId) ?: existing.contactId
                    // Two stored rows can resolve to one contact after a merge; keep it enabled if either was
                    existingStates[currentKey] = (existingStates[currentKey] ?: false) || existing.enabled
                }

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
                    database.contactDao().replaceAll(contactsToInsert)
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
                    rescanButton.text = "Rescan"
                }
            }
        }
    }

    private fun showTimePicker() {
        lifecycleScope.launch {
            val settings = withContext(Dispatchers.IO) {
                database.settingsDao().getSettingsSync() ?: AppSettings()
            }

            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(if (DateFormat.is24HourFormat(this@MainActivity)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(settings.sendTimeHour)
                .setMinute(settings.sendTimeMinute)
                .setTitleText("Send birthday texts at")
                .build()
            picker.addOnPositiveButtonClickListener {
                updateSendTime(picker.hour, picker.minute)
            }
            picker.show(supportFragmentManager, "sendTime")
        }
    }

    private fun updateSendTime(hour: Int, minute: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = database.settingsDao().getSettingsSync() ?: AppSettings()
            val updatedSettings = settings.copy(sendTimeHour = hour, sendTimeMinute = minute)
            database.settingsDao().updateSettings(updatedSettings)

            // The settings observer updates the button text; only the alarm needs a nudge here
            withContext(Dispatchers.Main) {
                scheduleAlarm()
            }
        }
    }

    private fun showEditMessageDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_edit_message, null)
        val input = view.findViewById<TextInputEditText>(R.id.messageInput)
        input.setText(currentSettings?.messageTemplate ?: AppSettings().messageTemplate)
        input.setSelection(input.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Birthday message")
            .setView(view)
            .setPositiveButton("Save") { _, _ -> saveMessageTemplate(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        // Open with the keyboard up, since editing is the only thing this dialog is for
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
        dialog.show()
    }

    private fun saveMessageTemplate(message: String) {
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
