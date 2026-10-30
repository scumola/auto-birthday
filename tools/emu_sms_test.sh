#!/usr/bin/env bash
# End-to-end SMS send test on an Android emulator, with no real texts sent: the emulator
# texts its own number, so whatever BirthdayAlarmReceiver sends lands in its own inbox.
#
# Usage (run in order):
#   tools/emu_sms_test.sh boot                 start the AVD headless + read-only (AVD untouched)
#   tools/emu_sms_test.sh prep APK             fresh-install APK, grant perms, add a test contact
#                                              whose birthday is today (device time) and whose
#                                              number is the emulator's own
#   tools/emu_sms_test.sh send "MESSAGE"       open the app, switch the contact on, save MESSAGE
#                                              (ASCII only: adb input can't type emoji), then fire.
#                                              Run once per prep: the row tap toggles the contact.
#   tools/emu_sms_test.sh setmsg "MESSAGE"     only change the saved message (app must be on screen)
#   tools/emu_sms_test.sh fire                 just fire the alarm receiver and print its log lines
#   tools/emu_sms_test.sh inbox                list SMS the emulator has received
#   tools/emu_sms_test.sh kill                 shut the emulator down
#
# Env: AVD (default Medium_Phone_API_36.1), EMU_PORT (default 5554).
set -euo pipefail

PKG=com.autobirthday
AVD="${AVD:-Medium_Phone_API_36.1}"
PORT="${EMU_PORT:-5554}"
SERIAL="emulator-$PORT"
OWN_NUMBER="1555521$PORT"   # emulator's own line is 1-555-521-<console port>
CONTACT_NAME="Multipart Test"
ADB=(adb -s "$SERIAL")
HERE="$(cd "$(dirname "$0")" && pwd)"
DIAG=("$HERE/diag.sh" -s "$SERIAL")

boot() {
    "$HOME/Android/Sdk/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim \
        -no-snapshot -read-only -port "$PORT" -gpu swiftshader_indirect > /dev/null 2>&1 &
    wait_boot
}

wait_boot() {
    "${ADB[@]}" wait-for-device
    until [[ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        sleep 2
    done
    echo "booted: $SERIAL, own number $OWN_NUMBER"
}

prep() {
    local apk="$1" today rid
    "${ADB[@]}" uninstall "$PKG" > /dev/null 2>&1 || true
    "${ADB[@]}" install "$apk" > /dev/null
    for p in READ_CONTACTS SEND_SMS POST_NOTIFICATIONS; do
        "${ADB[@]}" shell pm grant "$PKG" "android.permission.$p"
    done
    "${ADB[@]}" shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow

    # Contact rows only once per boot: the read-only AVD starts clean every time
    if "${ADB[@]}" shell content query --uri content://com.android.contacts/contacts \
        --projection display_name < /dev/null | grep -q "$CONTACT_NAME"; then
        echo "test contact already present"
        return
    fi
    # Birthday must match the *device's* today, which is what the receiver compares against
    today=$("${ADB[@]}" shell date +%Y-%m-%d | tr -d '\r')
    "${ADB[@]}" shell content insert --uri content://com.android.contacts/raw_contacts \
        --bind account_type:n: --bind account_name:n: < /dev/null
    rid=$("${ADB[@]}" shell content query --uri content://com.android.contacts/raw_contacts \
        --projection _id --sort "'_id DESC'" < /dev/null | head -1 | sed -n 's/.*_id=\([0-9]*\).*/\1/p')
    # Values with spaces are wrapped in single quotes because adb shell re-joins arguments
    "${ADB[@]}" shell content insert --uri content://com.android.contacts/data \
        --bind raw_contact_id:i:"$rid" --bind mimetype:s:vnd.android.cursor.item/name \
        --bind "data1:s:'$CONTACT_NAME'" < /dev/null
    "${ADB[@]}" shell content insert --uri content://com.android.contacts/data \
        --bind raw_contact_id:i:"$rid" --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
        --bind data1:s:"$OWN_NUMBER" --bind data2:i:2 < /dev/null
    "${ADB[@]}" shell content insert --uri content://com.android.contacts/data \
        --bind raw_contact_id:i:"$rid" --bind mimetype:s:vnd.android.cursor.item/contact_event \
        --bind data1:s:"$today" --bind data2:i:3 < /dev/null
    echo "added '$CONTACT_NAME' (raw contact $rid), birthday $today, number $OWN_NUMBER"
}

send() {
    local msg="$1"
    # Order matters: launch (first launch auto-scans contacts) -> switch the contact on ->
    # save the message -> fire the receiver. The receiver reads the DB, so it goes last.
    "${ADB[@]}" logcat -c
    "${ADB[@]}" shell am start -n "$PKG/.MainActivity" > /dev/null
    sleep 5
    # A freshly booted (software-rendered) emulator often throws a "System UI isn't
    # responding" dialog over everything; tap Wait on it if present.
    "${DIAG[@]}" tap android:id/aerr_wait 2>/dev/null && sleep 2 || true
    # Dismiss the exact-alarm / update dialogs if either appeared
    "${ADB[@]}" shell input keyevent KEYCODE_BACK; sleep 1
    "${ADB[@]}" shell am start -n "$PKG/.MainActivity" > /dev/null; sleep 2
    "${DIAG[@]}" tap nameTextView; sleep 1
    setmsg "$msg"
    fire
}

setmsg() {
    local msg="$1"
    "${DIAG[@]}" tap messageBubble; sleep 2
    "${ADB[@]}" shell input keycombination 113 29   # Ctrl+A
    "${ADB[@]}" shell input keyevent KEYCODE_DEL
    "${ADB[@]}" shell input text "'${msg// /%s}'"
    sleep 1
    "${ADB[@]}" shell input keyevent KEYCODE_BACK; sleep 1   # hide keyboard
    "${DIAG[@]}" tap android:id/button1; sleep 2              # Save
}

fire() {
    "${ADB[@]}" logcat -c
    # The receiver isn't exported, so fire it as the app itself (debug builds allow run-as).
    # --user 0: am defaults to "all users", which an app uid isn't allowed to target.
    "${ADB[@]}" shell run-as "$PKG" am broadcast --user 0 -n "$PKG/.BirthdayAlarmReceiver" > /dev/null
    sleep 6
    "${ADB[@]}" logcat -d -s BirthdayAlarm:* | grep -v '^-----' || true
}

inbox() {
    "${ADB[@]}" shell content query --uri content://sms/inbox --projection address:body:date < /dev/null
}

kill_emu() {
    "${ADB[@]}" emu kill
}

cmd="${1:-}"; shift || true
case "$cmd" in
    boot)  boot ;;
    wait)  wait_boot ;;
    prep)  prep "$@" ;;
    send)  send "$@" ;;
    setmsg) setmsg "$@" ;;
    fire)  fire ;;
    inbox) inbox ;;
    kill)  kill_emu ;;
    *) sed -n '2,20p' "$0"; exit 1 ;;
esac
