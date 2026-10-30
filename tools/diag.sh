#!/usr/bin/env bash
# Diagnostics for the AutoBirthday app on a connected phone (debug build only —
# run-as needs a debuggable APK).
#
# Usage:
#   tools/diag.sh [-s SERIAL] pulldb [OUTDIR]   copy the app's Room DB (+wal/shm) to OUTDIR
#                                               (default ./diag_out) and print the contacts table
#   tools/diag.sh [-s SERIAL] lookups [OUTFILE] dump the phone's current contacts
#                                               (_id, lookup key, name) via the contacts provider
#   tools/diag.sh [-s SERIAL] compare [OUTDIR]  pulldb + lookups, then list every stored contact
#                                               whose saved key no longer matches a live LOOKUP_KEY,
#                                               plus what the provider's lookup URI resolves it to
#   tools/diag.sh [-s SERIAL] resolve KEY       resolve a (possibly stale) lookup key through
#                                               content://com.android.contacts/contacts/lookup/KEY
#   tools/diag.sh [-s SERIAL] rawaccounts ID... show which account/app owns raw contact IDs
#                                               (the NNNN in a lookup key's "...rNNNN-..." segment)
#   tools/diag.sh [-s SERIAL] rescan            open the app and tap "Rescan" (phone must
#                                               be unlocked); waits a few seconds for it to finish
#   tools/diag.sh [-s SERIAL] tap VIEW_ID       tap the on-screen view with that resource id
#                                               (e.g. messageBubble, changeTimeButton)
#   tools/diag.sh [-s SERIAL] shot FILE.png     save a screenshot
#
# SERIAL defaults to $ANDROID_SERIAL, else adb's default device (pass -s when several are attached,
# e.g. -s <phone-ip>:<port> for wireless debugging).
# Output contains personal contact data — keep OUTDIR out of git.
set -euo pipefail

PKG=com.autobirthday
SERIAL="${ANDROID_SERIAL:-}"
if [[ "${1:-}" == "-s" ]]; then SERIAL="$2"; shift 2; fi
CMD="${1:-}"; shift || true
ADB=(adb)
if [[ -n "$SERIAL" ]]; then ADB+=(-s "$SERIAL"); fi

pulldb() {
    local out="${1:-./diag_out}"
    mkdir -p "$out"
    # Copy the WAL and SHM along with the main file: Room runs in WAL mode, so
    # recent writes (toggles, rescans) live in the -wal file until a checkpoint.
    for f in birthday_database birthday_database-wal birthday_database-shm; do
        "${ADB[@]}" exec-out run-as "$PKG" cat "databases/$f" > "$out/$f" 2>/dev/null || rm -f "$out/$f"
    done
    sqlite3 -header -column "$out/birthday_database" \
        "SELECT contactId, name, birthdayDate, enabled FROM contacts ORDER BY name;"
}

lookups() {
    local out="${1:-./diag_out/lookups.txt}"
    mkdir -p "$(dirname "$out")"
    "${ADB[@]}" shell content query --uri content://com.android.contacts/contacts \
        --projection _id:lookup:display_name > "$out"
    wc -l < "$out"
}

compare() {
    local out="${1:-./diag_out}"
    pulldb "$out" > /dev/null
    lookups "$out/lookups.txt" > /dev/null
    # Live lookup keys, one per line
    sed -n 's/.*lookup=\([^,]*\), display_name=.*/\1/p' "$out/lookups.txt" | sort -u > "$out/live_keys.txt"
    sqlite3 -separator $'\t' "$out/birthday_database" \
        "SELECT contactId, enabled, name FROM contacts ORDER BY name;" > "$out/stored.tsv"
    echo "stored contacts: $(wc -l < "$out/stored.tsv")   live contacts: $(wc -l < "$out/live_keys.txt")"
    echo "--- stored keys NOT found among live LOOKUP_KEYs (enabled flag, name, then live key for same name) ---"
    while IFS=$'\t' read -r key enabled name; do
        if ! grep -qxF -- "$key" "$out/live_keys.txt"; then
            live=$(grep -F "display_name=$name" "$out/lookups.txt" | sed -n 's/.*lookup=\([^,]*\),.*/\1/p' | head -3 | tr '\n' ' ')
            printf 'enabled=%s  %-28s stored=%s\n%38s live=%s\n' "$enabled" "$name" "$key" "" "${live:-<none>}"
            printf '%38s resolved=%s\n' "" "$(resolve "$key" | tr '\n' ' ')"
        fi
    done < "$out/stored.tsv"
}

resolve() {
    # </dev/null: adb shell would otherwise swallow the caller's stdin (the read loop above)
    "${ADB[@]}" shell content query \
        --uri "content://com.android.contacts/contacts/lookup/$1" \
        --projection _id:lookup:display_name < /dev/null
}

rawaccounts() {
    local ids
    ids=$(IFS=,; echo "$*")
    # The where clause is wrapped in single quotes because adb shell re-joins
    # arguments and the device shell would otherwise split it on spaces.
    "${ADB[@]}" shell content query --uri content://com.android.contacts/raw_contacts \
        --projection _id:account_type:contact_id:deleted --where "'_id IN ($ids)'" < /dev/null
}

tap() {
    local id="$1" xml bounds
    xml=$("${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null)
    bounds=$(grep -o "resource-id=\"$PKG:id/$id\"[^>]*bounds=\"[^\"]*\"" <<< "$xml" | head -1 \
        | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
    if [[ -z "$bounds" ]]; then
        echo "$id not found on screen (phone locked, or a dialog is covering the app?)" >&2
        return 1
    fi
    read -r x1 y1 x2 y2 <<< "$bounds"
    "${ADB[@]}" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
    echo "tapped $id at $(( (x1 + x2) / 2 )),$(( (y1 + y2) / 2 ))"
}

rescan() {
    # Launch first and give the activity time to draw, otherwise the UI dump
    # captures whatever was on screen before and the button isn't found.
    "${ADB[@]}" shell am start -n "$PKG/.MainActivity" > /dev/null
    sleep 3
    tap rescanButton
    sleep 5
}

shot() {
    "${ADB[@]}" exec-out screencap -p > "$1"
}

case "$CMD" in
    pulldb)  pulldb "$@" ;;
    lookups) lookups "$@" ;;
    compare) compare "$@" ;;
    resolve) resolve "$@" ;;
    rawaccounts) rawaccounts "$@" ;;
    rescan)  rescan "$@" ;;
    tap)     tap "$@" ;;
    shot)    shot "$@" ;;
    *) sed -n '2,26p' "$0"; exit 1 ;;
esac
