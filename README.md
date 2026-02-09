# Auto Birthday

An Android app that automatically sends birthday SMS wishes to your contacts. Scans Google Contacts for birthdays, maintains a local database, and sends customizable messages at a scheduled time.

## Features

- Automatically scans Google Contacts for birthday information
- Per-contact enable/disable for birthday messages
- Customizable message templates
- Configurable send time (default: 9:00 AM)
- Handles multiple birthdays on the same day
- Survives device reboots (re-schedules alarms)
- Simple single-screen UI

## Architecture

- **Room Database**: Stores contacts, birthdays, and settings
- **AlarmManager**: Schedules daily checks at configured time
- **ContactScanner**: Reads Google Contacts birthday data
- **BirthdayAlarmReceiver**: Sends SMS on birthday matches
- **BootReceiver**: Re-schedules alarms after device reboot

## Permissions

- `READ_CONTACTS` - Access birthday data
- `SEND_SMS` - Send birthday messages
- `SCHEDULE_EXACT_ALARM` - Wake device at scheduled time
- `POST_NOTIFICATIONS` - Show notification feedback
- `RECEIVE_BOOT_COMPLETED` - Reschedule after reboot

## Build

```bash
./gradlew assembleDebug
```

Requires Android SDK with API 34. Min SDK: API 26 (Android 8.0).

## License

MIT
