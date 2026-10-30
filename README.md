# Auto Birthday

An Android app that automatically sends birthday SMS wishes to your contacts. Scans Google Contacts for birthdays, maintains a local database, and sends customizable messages at a scheduled time.

## Features

- Automatically scans Google Contacts for birthday information
- Per-contact enable/disable for birthday messages
- Customizable message templates
- Configurable send time (default: 9:00 AM)
- Handles multiple birthdays on the same day
- Survives device reboots (re-schedules alarms)
- Single screen: who gets the next text and when, a preview of the message as it will be sent, and the list sorted by soonest birthday
- Enabled states survive rescans even when a linked app (e.g. Telegram) changes a contact's lookup key

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

Unit tests (date logic): `./gradlew testDebugUnitTest`

## Diagnostics

`tools/diag.sh` inspects the app on a connected phone over adb (debug builds): compare stored contact
keys with the phone's live ones, pull the database, tap views, take screenshots. Run it with no
arguments for usage. Its output contains contact names and numbers, so it writes to the gitignored
`diag_out/` by default.

## License

MIT. Bundled font Unbounded is under the SIL Open Font License (`licenses/Unbounded-OFL.txt`).
