package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GET /api/anthropic_auths/usage_history, as agreed with the server (2026-10-12). */
class UsageHistoryTest {
    private val response = """{
      "tz": "Europe/Oslo", "from": "2026-10-09", "to": "2026-10-12", "generated_at": "2026-10-12T10:31:07Z",
      "accounts": [{
        "id": 3, "label": "Personal",
        "weekly_resets_at": "2026-10-16T07:00:00Z",
        "reset_times": ["2026-10-09T07:00:00Z"],
        "days": [
          {"date": "2026-10-09", "weekly_used_pct": 12.5, "weekly_pct_start_of_day": 95.0, "weekly_pct_end_of_day": 7.5,
           "reset_in_day": true, "samples": 288, "partial": false},
          {"date": "2026-10-10", "weekly_used_pct": null, "weekly_pct_start_of_day": null, "weekly_pct_end_of_day": null,
           "reset_in_day": null, "samples": 0, "partial": false},
          {"date": "2026-10-11", "weekly_used_pct": 0.0, "weekly_pct_start_of_day": 7.5, "weekly_pct_end_of_day": 7.5,
           "reset_in_day": false, "samples": 96, "partial": false},
          {"date": "2026-10-12", "weekly_used_pct": 18, "weekly_pct_start_of_day": 7.5, "weekly_pct_end_of_day": 25.5,
           "reset_in_day": false, "samples": 40, "partial": true}
        ],
        "samples": [
          {"at": "2026-10-09T06:55:00Z", "weekly_pct": 95.0, "weekly_resets_at": "2026-10-09T07:00:00Z"},
          {"at": "2026-10-09T07:05:00Z", "weekly_pct": 0.5, "weekly_resets_at": "2026-10-16T07:00:00Z"},
          {"at": "2026-10-12T09:00:00Z", "weekly_pct": null, "weekly_resets_at": null},
          {"at": "2026-10-12T10:30:00Z", "weekly_pct": 25.5, "weekly_resets_at": "2026-10-16T07:00:00Z"}
        ]
      }]
    }"""

    private fun history(s: String = response) = UsageHistory.from(Json.parseToJsonElement(s).jsonObject)

    @Test
    fun daysKeepGapsApartFromZerosAndNeverGoNegative() {
        val days = history().accounts.single().days
        assertEquals(listOf("2026-10-09", "2026-10-10", "2026-10-11", "2026-10-12"), days.map { it.date })
        assertEquals(listOf(12.5, null, 0.0, 18.0), days.map { it.usedPct })
        assertFalse(days[1].hasData)
        assertTrue(days[2].hasData)
        // A reset day: the start is above the end, and the day's use is still the true sum.
        assertEquals(true, days[0].resetInDay)
        assertTrue(days[0].startPct!! > days[0].endPct!!)
        assertTrue(days[3].partial)
        // A negative from a broken server reads as no use, not as a bar below zero.
        val negative = history(response.replace("\"weekly_used_pct\": 12.5", "\"weekly_used_pct\": -3")).accounts.single().days[0]
        assertEquals(0.0, negative.usedPct)
    }

    @Test
    fun resetsComeFromTheAccountAndElseItsNewestReading() {
        val a = history().accounts.single()
        assertEquals(parseServerTime("2026-10-16T07:00:00Z"), a.weeklyResetsAtMs)
        assertEquals(listOf(parseServerTime("2026-10-09T07:00:00Z")), a.resetTimesMs)
        val older = history(response.replace("\"weekly_resets_at\": \"2026-10-16T07:00:00Z\",\n        \"reset_times\"", "\"reset_times\""))
            .accounts.single()
        assertEquals(parseServerTime("2026-10-16T07:00:00Z"), older.weeklyResetsAtMs, "from the newest reading that has one")
    }

    @Test
    fun theCurrentWeekRunsFromTheLastResetAndRollsForward() {
        val a = history().accounts.single()
        val now = parseServerTime("2026-10-12T10:31:00Z")!!
        val week = currentWeek(a, now)!!
        assertEquals(parseServerTime("2026-10-09T07:00:00Z"), week.startMs)
        assertEquals(parseServerTime("2026-10-16T07:00:00Z"), week.resetMs)
        // Readings of this period with a value only: not the one before the reset, nor the one without a value.
        assertEquals(listOf(0.5, 25.5), week.points.map { it.weeklyPct })
        assertEquals(25.5, week.latestPct)
        // After the reset with no newer reading, the week has moved on, and nothing is in it yet.
        val later = currentWeek(a, parseServerTime("2026-10-17T08:00:00Z")!!)!!
        assertEquals(parseServerTime("2026-10-23T07:00:00Z"), later.resetMs)
        assertNull(later.latestPct)
        // An account that never saw a reset has no week to draw.
        assertNull(currentWeek(a.copy(weeklyResetsAtMs = null), now))
    }

    @Test
    fun withoutRawThereAreNoSamples() {
        val a = history(response.substringBefore(",\n        \"samples\": [") + "\n      }]\n    }").accounts.single()
        assertTrue(a.samples.isEmpty())
        assertEquals(4, a.days.size)
    }
}
