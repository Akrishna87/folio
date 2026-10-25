package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.stats.ReadingStats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReadingStatsTest {
    private val today = LocalDate.of(2026, 10, 25)

    @Test
    fun streakCountsDaysInARowThatMetTheGoal() {
        var s = ReadingStats(goalMinutes = 10)
        for (d in 1L..3L) s = s.add(today.minusDays(d), 600) // the three days before today
        s = s.add(today.minusDays(5), 900) // an earlier day, after a gap
        // Today hasn't met the goal yet: the streak from yesterday still counts.
        assertEquals(3, s.streak(today))
        s = s.add(today, 300).add(today, 300)
        assertEquals(600, s.seconds(today))
        assertEquals(4, s.streak(today))
        // A missed day breaks it.
        assertEquals(0, s.streak(today.plusDays(2)))
        assertEquals(4, s.bestStreak())
    }

    @Test
    fun weekAndJson() {
        val s = ReadingStats(goalMinutes = 15).add(today, 120).add(today.minusDays(6), 60).add(today.minusDays(7), 999)
        assertEquals(listOf(60, 0, 0, 0, 0, 0, 120), s.week(today))
        assertEquals(s, ReadingStats.fromJson(JSONObject(s.toJson().toString())))
        assertEquals(ReadingStats.DEFAULT_GOAL, ReadingStats.fromJson(JSONObject("{}")).goalMinutes)
    }

    @Test
    fun oldDaysAreDropped() {
        val s = ReadingStats().add(today.minusDays(500), 60).add(today, 60)
        assertEquals(listOf(today.toString()), s.secondsByDay.keys.toList())
    }
}
