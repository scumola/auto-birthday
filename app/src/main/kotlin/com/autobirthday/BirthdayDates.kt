package com.autobirthday

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit

// Date math for the UI: when each birthday next comes around and who gets the next text.
// Mirrors how BirthdayAlarmReceiver actually sends (literal "MM-dd" match on the day the
// alarm fires), so what the screen promises is what the alarm will do.
object BirthdayDates {

    data class NextSend(val date: LocalDate, val contacts: List<ContactEntity>)

    fun nextOccurrence(mmdd: String, from: LocalDate): LocalDate? {
        val monthDay = try {
            MonthDay.parse("--$mmdd")
        } catch (e: Exception) {
            return null
        }
        // Feb 29 only exists in leap years, and the alarm only matches "02-29" on that literal
        // day, so skip ahead to the next year where it's a real date.
        var year = from.year
        while (true) {
            if (monthDay.isValidYear(year)) {
                val date = monthDay.atYear(year)
                if (!date.isBefore(from)) return date
            }
            year++
        }
    }

    // Long.MAX_VALUE for unparseable dates so they sort to the end of the list
    fun daysUntil(mmdd: String, today: LocalDate): Long {
        val next = nextOccurrence(mmdd, today) ?: return Long.MAX_VALUE
        return ChronoUnit.DAYS.between(today, next)
    }

    fun nextSend(contacts: List<ContactEntity>, sendTime: LocalTime, now: LocalDateTime): NextSend? {
        val today = now.toLocalDate()
        // Once today's send time has passed, today's alarm has already gone out, so a birthday
        // today is next due a year from now.
        val firstSendDay = if (now.toLocalTime().isBefore(sendTime)) today else today.plusDays(1)

        val upcoming = contacts
            .filter { it.enabled }
            .mapNotNull { contact -> nextOccurrence(contact.birthdayDate, firstSendDay)?.let { it to contact } }
        val soonest = upcoming.minOfOrNull { it.first } ?: return null
        return NextSend(soonest, upcoming.filter { it.first == soonest }.map { it.second })
    }

    fun relativeLabel(days: Long): String = when (days) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> "In $days days"
    }

    fun namesLabel(contacts: List<ContactEntity>): String = when (contacts.size) {
        0 -> ""
        1 -> contacts[0].name
        2 -> "${contacts[0].name} and ${contacts[1].name}"
        else -> "${contacts[0].name} and ${contacts.size - 1} others"
    }
}
