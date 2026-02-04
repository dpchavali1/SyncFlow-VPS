package com.phoneintegration.app.deals.notify

import java.time.LocalDate
import java.time.Month

object HolidayCalendar {

    fun isHoliday(date: LocalDate): Boolean {
        val year = date.year

        return calculateHolidays(year).any { it == date }
    }

    private fun calculateHolidays(year: Int): List<LocalDate> {
        return listOf(
            LocalDate.of(year, Month.JANUARY, 1),        // New Year
            nthWeekdayOfMonth(3, java.time.DayOfWeek.MONDAY, Month.JANUARY, year), // MLK
            nthWeekdayOfMonth(3, java.time.DayOfWeek.MONDAY, Month.FEBRUARY, year), // Presidents Day
            lastWeekdayOfMonth(java.time.DayOfWeek.MONDAY, Month.MAY, year), // Memorial Day
            LocalDate.of(year, Month.JUNE, 19),          // Juneteenth
            LocalDate.of(year, Month.JULY, 4),           // Independence Day
            nthWeekdayOfMonth(1, java.time.DayOfWeek.MONDAY, Month.SEPTEMBER, year), // Labor Day
            nthWeekdayOfMonth(4, java.time.DayOfWeek.THURSDAY, Month.NOVEMBER, year), // Thanksgiving
            LocalDate.of(year, Month.DECEMBER, 25)       // Christmas
        )
    }

    private fun nthWeekdayOfMonth(
        nth: Int,
        dow: java.time.DayOfWeek,
        month: Month,
        year: Int
    ): LocalDate {
        var date = LocalDate.of(year, month, 1)
        var count = 0

        while (true) {
            if (date.dayOfWeek == dow) {
                count++
                if (count == nth) return date
            }
            date = date.plusDays(1)
        }
    }

    private fun lastWeekdayOfMonth(
        dow: java.time.DayOfWeek,
        month: Month,
        year: Int
    ): LocalDate {
        val isLeap = java.time.Year.isLeap(year.toLong())
        var date = LocalDate.of(year, month, month.length(isLeap))
        while (date.dayOfWeek != dow) {
            date = date.minusDays(1)
        }
        return date
    }
}