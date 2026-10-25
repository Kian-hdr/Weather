package dev.kiankonrad.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ForecastProjectionTest {
    private fun epoch(value: String) = Instant.parse(value).epochSecond
    private val start = epoch("2026-10-25T09:00:00Z") // 13:00 in Dubai
    private fun hour(time: Long, temperature: Double?, code: Int = 1, direction: Double? = 350.0) = Hour(
        time, temperature, code, 20.0, 0.2, 10.0, 15.0, 50.0, 12.0,
        1010.0, 10000.0, 4.0, temperature?.minus(1.0), direction
    )
    private fun day(date: String, rise: String, set: String) = Day(
        date, 1, 30.0, 20.0, epoch(rise), epoch(set), 7.0, 0.0, 20.0
    )
    private fun weather(hours: List<Hour> = (0L..5L).map { hour(start + it * 3600, 30.0 - it) }) = Weather(
        City("dubai", "Dubai", "UAE", 25.2, 55.3, "Asia/Dubai"),
        Conditions(start, 30.0, 29.0, 1, true, 50.0, 10.0, 15.0, 350.0, 1010.0),
        hours,
        listOf(day("2026-10-25", "2026-10-25T02:20:00Z", "2026-10-25T13:40:00Z"),
            day("2026-10-26", "2026-10-26T02:20:00Z", "2026-10-26T13:40:00Z")),
        AirQuality(start, 10.0, 20.0, 2.0, 3.0), start + 20, "Asia/Dubai"
    )

    @Test fun threeHoursOfflineUsesTheSavedForecastForThatTime() {
        val original = weather()
        val now = start + 3 * 3600
        val shown = original.atTime(now)
        assertEquals(ConditionSource.FORECAST, original.conditionSourceAt(now))
        assertEquals(27.0, shown.current.temperature!!, 0.001)
        assertEquals(now, shown.current.time)
        assertEquals(original.fetchedAt, shown.fetchedAt)
        assertEquals(30.0, original.current.temperature!!, 0.001)
    }

    @Test fun currentProviderConditionsHaveABoundedFreshWindow() {
        val original = weather()
        assertEquals(ConditionSource.CURRENT, original.conditionSourceAt(start + 900))
        assertEquals(30.0, original.atTime(start + 900).current.temperature!!, 0.001)
        assertEquals(ConditionSource.FORECAST, original.conditionSourceAt(start + 901))
        assertEquals(ConditionSource.UNAVAILABLE, original.conditionSourceAt(start - 1))
    }

    @Test fun interpolatesContinuousValuesAndUsesContainingHourCode() {
        val left = hour(start, 20.0, 3).copy(wind = 10.0, humidity = 40.0)
        val right = hour(start + 3600, 26.0, 61, 10.0).copy(wind = 20.0, humidity = 60.0)
        val original = weather(listOf(left, right))
        val shown = original.atTime(start + 1800)
        assertEquals(23.0, shown.current.temperature!!, 0.001)
        assertEquals(22.0, shown.current.feelsLike!!, 0.001)
        assertEquals(15.0, shown.current.wind!!, 0.001)
        assertEquals(50.0, shown.current.humidity!!, 0.001)
        assertEquals(0.0, shown.current.windDirection!!, 0.001)
        assertEquals(3, shown.current.code)
        assertEquals(61, original.atTime(start + 3600).current.code)
    }

    @Test fun missingEndpointValuesStayMissingInsteadOfBecomingZeroOrOldValues() {
        val original = weather(listOf(hour(start, 20.0), hour(start + 3600, null).copy(wind = null)))
        val shown = original.atTime(start + 1800)
        assertNull(shown.current.temperature)
        assertNull(shown.current.feelsLike)
        assertNull(shown.current.wind)
        assertEquals(50.0, shown.current.humidity!!, 0.001)
    }

    @Test fun missingHoursAreNotBridgedAndHorizonIsNeverExtrapolated() {
        val original = weather(listOf(hour(start, 20.0), hour(start + 7200, 26.0)))
        assertEquals(ConditionSource.UNAVAILABLE, original.conditionSourceAt(start + 3600))
        assertNull(original.atTime(start + 3600).current.temperature)
        assertEquals(26.0, original.atTime(start + 7200).current.temperature!!, 0.001)
        assertNull(original.atTime(start + 7201).current.temperature)
        assertNull(original.atTime(start + 7201).current.code)
    }

    @Test fun lastForecastSampleIsUsableOnlyAtItsExactTime() {
        val original = weather()
        assertEquals(25.0, original.atTime(start + 5 * 3600).current.temperature!!, 0.001)
        assertEquals(ConditionSource.UNAVAILABLE, original.conditionSourceAt(start + 5 * 3600 + 1))
    }

    @Test fun localMidnightDropsYesterdaysDailySummary() {
        val now = epoch("2026-10-25T20:30:00Z") // 00:30 on October 26 in Dubai
        val shown = weather(listOf(hour(now - 1800, 25.0), hour(now + 1800, 23.0))).atTime(now)
        assertEquals(listOf("2026-10-26"), shown.days.map { it.date })
        assertFalse(shown.current.isDay)
        assertEquals(24.0, shown.current.temperature!!, 0.001)
    }

    @Test fun repeatedDstHourUsesEpochOrderRatherThanAmbiguousClockText() {
        val first = epoch("2026-10-25T00:30:00Z")
        val second = epoch("2026-10-25T01:30:00Z")
        val original = weather(listOf(hour(first, 10.0), hour(second, 6.0)))
            .copy(timezone = "Europe/Berlin", current = weather().current.copy(time = first - 3600))
        assertEquals("02:30", localTime(first, original.timezone))
        assertEquals("02:30", localTime(second, original.timezone))
        assertEquals(10.0, original.atTime(first).current.temperature!!, 0.001)
        assertEquals(6.0, original.atTime(second).current.temperature!!, 0.001)
    }

    @Test fun oldOrFutureAirQualityDoesNotBecomeACurrentEstimate() {
        val original = weather()
        assertNotNull(original.atTime(start + 3600).air)
        assertNull(original.atTime(start + 3601).air)
        assertNull(original.atTime(start - 1).air)
    }

    @Test fun daylightFollowsTheSelectedDatesSunriseAndSunset() {
        val sunset = epoch("2026-10-25T13:40:00Z")
        val original = weather()
        assertTrue(original.atTime(sunset - 1).current.isDay)
        assertFalse(original.atTime(sunset).current.isDay)
    }

    @Test fun unsortedSamplesStillChooseChronologicalNeighbors() {
        val original = weather(listOf(hour(start + 3600, 24.0), hour(start, 20.0)))
        assertEquals(22.0, original.atTime(start + 1800).current.temperature!!, 0.001)
    }

    @Test fun invalidTimezoneAndMissingSolarDataUseSafeArtworkFallback() {
        val original = weather().copy(timezone = "Invalid/Zone", days = emptyList())
        val shown = original.atTime(start + 3600)
        assertTrue(shown.current.isDay) // UTC 10:00; artwork only, no solar data is fabricated.
        assertTrue(shown.days.isEmpty())
    }
}
