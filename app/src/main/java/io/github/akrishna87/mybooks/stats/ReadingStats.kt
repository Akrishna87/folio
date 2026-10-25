package io.github.akrishna87.mybooks.stats

import org.json.JSONObject
import java.time.LocalDate

/**
 * Time spent reading or listening, per day, and the daily goal. Days are local dates
 * ("2026-10-25"); only the last year or so is kept.
 */
data class ReadingStats(val secondsByDay: Map<String, Int> = emptyMap(), val goalMinutes: Int = DEFAULT_GOAL) {

    fun seconds(day: LocalDate): Int = secondsByDay[day.toString()] ?: 0

    fun add(day: LocalDate, seconds: Int): ReadingStats {
        if (seconds <= 0) return this
        val key = day.toString()
        val cutoff = day.minusDays(400).toString()
        val kept = secondsByDay.filterKeys { it >= cutoff } // ISO dates sort as text
        return copy(secondsByDay = kept + (key to (kept[key] ?: 0) + seconds))
    }

    fun metGoal(day: LocalDate) = seconds(day) >= goalMinutes * 60

    /**
     * Days in a row the goal was met, up to today. Today only breaks the run once it's over, so
     * a streak isn't lost in the morning before you've read.
     */
    fun streak(today: LocalDate): Int {
        var day = if (metGoal(today)) today else today.minusDays(1)
        var n = 0
        while (metGoal(day)) {
            n++
            day = day.minusDays(1)
        }
        return n
    }

    /** The longest run of goal days ever. */
    fun bestStreak(): Int {
        val days = secondsByDay.filter { it.value >= goalMinutes * 60 }.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        return best
    }

    /** Seconds on each of the last seven days, oldest first, ending today. */
    fun week(today: LocalDate): List<Int> = (6 downTo 0).map { seconds(today.minusDays(it.toLong())) }

    fun toJson(): JSONObject = JSONObject()
        .put("goal", goalMinutes)
        .put("days", JSONObject(secondsByDay as Map<*, *>))

    companion object {
        const val DEFAULT_GOAL = 10
        val GOALS = listOf(5, 10, 15, 20, 30, 45, 60, 90)

        fun fromJson(o: JSONObject): ReadingStats {
            val days = o.optJSONObject("days") ?: JSONObject()
            return ReadingStats(days.keys().asSequence().associateWith { days.optInt(it) }, o.optInt("goal", DEFAULT_GOAL))
        }
    }
}
