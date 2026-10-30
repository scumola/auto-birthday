package com.autobirthday

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class BirthdayDatesTest {

    private fun contact(name: String, mmdd: String, enabled: Boolean = true) =
        ContactEntity(contactId = name, name = name, phoneNumber = "555", birthdayDate = mmdd, enabled = enabled)

    @Test
    fun `next occurrence later this year`() {
        assertEquals(LocalDate.of(2026, 11, 27), BirthdayDates.nextOccurrence("11-27", LocalDate.of(2026, 10, 29)))
    }

    @Test
    fun `next occurrence today is today`() {
        assertEquals(LocalDate.of(2026, 10, 29), BirthdayDates.nextOccurrence("10-29", LocalDate.of(2026, 10, 29)))
    }

    @Test
    fun `next occurrence wraps into next year`() {
        assertEquals(LocalDate.of(2027, 1, 14), BirthdayDates.nextOccurrence("01-14", LocalDate.of(2026, 10, 29)))
    }

    @Test
    fun `feb 29 only lands on a leap year because the alarm matches the literal date`() {
        assertEquals(LocalDate.of(2028, 2, 29), BirthdayDates.nextOccurrence("02-29", LocalDate.of(2026, 10, 29)))
    }

    @Test
    fun `malformed dates are ignored`() {
        assertNull(BirthdayDates.nextOccurrence("13-40", LocalDate.of(2026, 10, 29)))
        assertNull(BirthdayDates.nextOccurrence("", LocalDate.of(2026, 10, 29)))
    }

    @Test
    fun `days until counts calendar days`() {
        val today = LocalDate.of(2026, 10, 29)
        assertEquals(0L, BirthdayDates.daysUntil("10-29", today))
        assertEquals(1L, BirthdayDates.daysUntil("10-30", today))
        assertEquals(29L, BirthdayDates.daysUntil("11-27", today))
        assertEquals(Long.MAX_VALUE, BirthdayDates.daysUntil("bad", today))
    }

    @Test
    fun `next send picks the soonest enabled date and everyone on it`() {
        val contacts = listOf(
            contact("Lee", "11-27"),
            contact("Katie", "09-24"),
            contact("Matt", "09-24"),
            contact("Steve", "11-01", enabled = false)
        )
        val next = BirthdayDates.nextSend(contacts, LocalTime.of(9, 0), LocalDateTime.of(2026, 10, 29, 12, 0))!!
        assertEquals(LocalDate.of(2026, 11, 27), next.date)
        assertEquals(listOf("Lee"), next.contacts.map { it.name })

        val sameDay = BirthdayDates.nextSend(contacts, LocalTime.of(9, 0), LocalDateTime.of(2026, 9, 1, 12, 0))!!
        assertEquals(LocalDate.of(2026, 9, 24), sameDay.date)
        assertEquals(listOf("Katie", "Matt"), sameDay.contacts.map { it.name })
    }

    @Test
    fun `birthday today counts until the send time passes`() {
        val contacts = listOf(contact("Lee", "10-29"), contact("Jeff", "12-01"))
        val before = BirthdayDates.nextSend(contacts, LocalTime.of(9, 0), LocalDateTime.of(2026, 10, 29, 8, 59))!!
        assertEquals(LocalDate.of(2026, 10, 29), before.date)

        val after = BirthdayDates.nextSend(contacts, LocalTime.of(9, 0), LocalDateTime.of(2026, 10, 29, 9, 0))!!
        assertEquals(LocalDate.of(2026, 12, 1), after.date)
        assertEquals(listOf("Jeff"), after.contacts.map { it.name })
    }

    @Test
    fun `relative labels read naturally`() {
        assertEquals("Today", BirthdayDates.relativeLabel(0))
        assertEquals("Tomorrow", BirthdayDates.relativeLabel(1))
        assertEquals("In 29 days", BirthdayDates.relativeLabel(29))
    }

    @Test
    fun `next up names collapse past two people`() {
        assertEquals("Lee", BirthdayDates.namesLabel(listOf(contact("Lee", "11-27"))))
        assertEquals("Katie and Matt", BirthdayDates.namesLabel(listOf(contact("Katie", "09-24"), contact("Matt", "09-24"))))
        assertEquals("Katie and 2 others", BirthdayDates.namesLabel(listOf(contact("Katie", "09-24"), contact("Matt", "09-24"), contact("Zed", "09-24"))))
    }

    @Test
    fun `nothing enabled means nothing scheduled`() {
        val contacts = listOf(contact("Lee", "11-27", enabled = false))
        assertNull(BirthdayDates.nextSend(contacts, LocalTime.of(9, 0), LocalDateTime.of(2026, 10, 29, 12, 0)))
    }
}
