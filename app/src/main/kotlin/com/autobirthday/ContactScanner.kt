package com.autobirthday

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactScanner(private val context: Context) {

    suspend fun scanContacts(): List<ContactEntity> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<ContactEntity>()
        val contentResolver: ContentResolver = context.contentResolver

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )

        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        )

        cursor?.use { contactCursor ->
            while (contactCursor.moveToNext()) {
                val idIndex = contactCursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = contactCursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIndex = contactCursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                if (idIndex < 0 || nameIndex < 0 || hasPhoneIndex < 0) continue

                val contactId = contactCursor.getString(idIndex)
                val name = contactCursor.getString(nameIndex) ?: continue
                val hasPhone = contactCursor.getInt(hasPhoneIndex) > 0

                android.util.Log.d("ContactScanner", "Checking contact: $name (ID: $contactId)")

                if (!hasPhone) {
                    android.util.Log.d("ContactScanner", "  -> Skipped: No phone number flag")
                    continue
                }

                val birthday = getBirthday(contentResolver, contactId)
                if (birthday.isNullOrEmpty()) {
                    android.util.Log.d("ContactScanner", "  -> Skipped: No birthday found")
                    continue
                }

                android.util.Log.d("ContactScanner", "  -> Birthday found: $birthday")

                val phoneNumber = getPhoneNumber(contentResolver, contactId)
                if (phoneNumber.isNullOrEmpty()) {
                    android.util.Log.d("ContactScanner", "  -> Skipped: No phone number retrieved")
                    continue
                }

                android.util.Log.d("ContactScanner", "  -> Added with phone: $phoneNumber")

                contacts.add(
                    ContactEntity(
                        contactId = contactId,
                        name = name,
                        phoneNumber = phoneNumber,
                        birthdayDate = birthday,
                        enabled = false
                    )
                )
            }
        }

        contacts
    }

    private fun getBirthday(contentResolver: ContentResolver, contactId: String): String? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Event.START_DATE
        )

        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = ? AND " +
                "${ContactsContract.CommonDataKinds.Event.TYPE} = ?"

        val selectionArgs = arrayOf(
            contactId,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
        )

        val cursor = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val dateIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)
                if (dateIndex >= 0) {
                    val dateString = it.getString(dateIndex)
                    android.util.Log.d("ContactScanner", "     Raw birthday data: '$dateString'")
                    val parsed = parseBirthdayDate(dateString)
                    android.util.Log.d("ContactScanner", "     Parsed to: '$parsed'")
                    return parsed
                }
            }
        }

        return null
    }

    private fun parseBirthdayDate(dateString: String?): String? {
        if (dateString.isNullOrEmpty()) return null

        try {
            // Handle various date formats: YYYY-MM-DD, --MM-DD, MM-DD
            // --MM-DD splits into ["", "", "MM", "DD"] (size 4)
            // YYYY-MM-DD splits into ["YYYY", "MM", "DD"] (size 3)
            // MM-DD splits into ["MM", "DD"] (size 2)
            val parts = dateString.split("-")
            return when {
                parts.size == 4 && parts[0].isEmpty() && parts[1].isEmpty() -> {
                    // --MM-DD format (no year)
                    val month = parts[2].padStart(2, '0')
                    val day = parts[3].padStart(2, '0')
                    "$month-$day"
                }
                parts.size == 3 -> {
                    // YYYY-MM-DD format
                    val month = parts[1].padStart(2, '0')
                    val day = parts[2].padStart(2, '0')
                    "$month-$day"
                }
                parts.size == 2 -> {
                    // MM-DD format
                    val month = parts[0].padStart(2, '0')
                    val day = parts[1].padStart(2, '0')
                    "$month-$day"
                }
                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun getPhoneNumber(contentResolver: ContentResolver, contactId: String): String? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ? AND " +
                "${ContactsContract.CommonDataKinds.Phone.TYPE} = ?"

        val selectionArgs = arrayOf(
            contactId,
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE.toString()
        )

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) {
                    return it.getString(numberIndex)
                }
            }
        }

        // If no mobile number found, try to get any phone number
        val anyPhoneCursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )

        anyPhoneCursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) {
                    return it.getString(numberIndex)
                }
            }
        }

        return null
    }
}
