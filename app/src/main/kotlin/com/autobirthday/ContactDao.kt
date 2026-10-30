package com.autobirthday

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAllContacts(): LiveData<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    suspend fun getAllContactsSync(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE enabled = 1")
    suspend fun getEnabledContacts(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contacts: List<ContactEntity>)

    @Update
    suspend fun update(contact: ContactEntity)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()

    // Rescan replaces the whole table. One transaction means a kill between the delete and
    // the insert can't leave the table empty (losing every enabled state), and observers
    // never see the empty in-between list.
    @Transaction
    suspend fun replaceAll(contacts: List<ContactEntity>) {
        deleteAll()
        insertAll(contacts)
    }

    @Query("SELECT * FROM contacts WHERE birthdayDate = :todayDate AND enabled = 1")
    suspend fun getTodaysBirthdays(todayDate: String): List<ContactEntity>
}
