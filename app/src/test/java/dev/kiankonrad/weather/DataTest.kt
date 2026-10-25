package dev.kiankonrad.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DataTest {
    private val city = City("berlin", "Berlin", "Germany", 52.52, 13.41, "Europe/Berlin")
    private fun forecast() = JSONObject("""{
      "timezone":"Europe/Berlin",
      "current":{"time":1792144800,"temperature_2m":12.4,"is_day":1,"weather_code":3},
      "hourly":{"time":[1792144800,1792148400],"temperature_2m":[12.4,null],"precipitation_probability":[0,null]},
      "daily":{"time":[1792101600],"temperature_2m_max":[15.1],"temperature_2m_min":[7.2],"sunrise":[1792126800]}
    }""")

    @Test fun absentAndExplicitNullValuesAreNotZero() {
        val parsed = DataCodec.forecast(forecast(), city, null, 1792144900)
        assertEquals(12.4, parsed.current.temperature!!, 0.001)
        assertNull(parsed.current.humidity)
        assertNull(parsed.hours[1].temperature)
        assertEquals(0.0, parsed.hours[0].precipitationProbability!!, 0.0)
        assertNull(parsed.hours[1].precipitationProbability)
        assertNull(parsed.days[0].sunset)
        assertNull(parsed.air)
    }
    @Test fun polarSunriseAndSunsetZeroEpochsRemainUnavailable() {
        val raw = forecast()
        raw.getJSONObject("daily").put("sunrise", JSONArray().put(0)).put("sunset", JSONArray().put(0))
        val parsed = DataCodec.forecast(raw, city, null, 1792144900)
        assertNull(parsed.days.single().sunrise)
        assertNull(parsed.days.single().sunset)
    }
    @Test fun cachedForecastUsesRequestedCityIdentityForSharedCoordinates() {
        val original = city.copy(id = "old-city-id", name = "Old display name")
        val requested = city.copy(id = "current-location", name = "My Location")
        val envelope = JSONObject().put("city", DataCodec.cityJson(original))
            .put("forecast", forecast()).put("fetchedAt", 1792144900)
        val parsed = DataCodec.cachedForecast(envelope, requested)
        assertEquals(requested.id, parsed.city.id)
        assertEquals(requested.name, parsed.city.name)
        assertEquals(requested.latitude, parsed.city.latitude, 0.0)
        assertEquals("Europe/Berlin", parsed.city.timezone)
    }
    @Test fun persistenceRoundTripRetainsCityAndMissingData() {
        val cities = DataCodec.cities(JSONArray().put(DataCodec.cityJson(city)))
        assertEquals(listOf(city), cities)
        val grid = DataCodec.gridCoordinates(12.0, 50.0, 14.0, 53.0).copy(fetchedAt = 100)
        val restored = DataCodec.grid(JSONObject(DataCodec.gridJson(grid).toString()))
        assertEquals(grid, restored)
        assertNull(restored.points[0].temperature)
    }
    @Test fun timestampsRemainEpochSecondsAndUseLocationTimezone() {
        val parsed = DataCodec.forecast(forecast(), city, null, 1792144900)
        assertEquals(1792144800, parsed.current.time)
        assertEquals("Europe/Berlin", parsed.timezone)
        assertEquals("2026-10-16", parsed.days[0].date)
    }
    @Test fun daylightSavingRepeatedHourIsConvertedFromEachInstant() {
        val first = Instant.parse("2026-10-25T00:30:00Z").epochSecond
        val second = Instant.parse("2026-10-25T01:30:00Z").epochSecond
        assertEquals("02:30 +02:00", localTime(first, "Europe/Berlin", "HH:mm XXX"))
        assertEquals("02:30 +01:00", localTime(second, "Europe/Berlin", "HH:mm XXX"))
    }
    @Test fun dailyDatesPreserveProviderCalendarAcrossDstFallback() {
        listOf(Triple("Europe/Berlin", 7200, "2026-10-24T22:00:00Z"),
            Triple("America/New_York", -14400, "2026-11-01T04:00:00Z")).forEach { (zone, offset, start) ->
            val raw = forecast().put("timezone", zone).put("utc_offset_seconds", offset)
            val first = Instant.parse(start).epochSecond
            raw.getJSONObject("daily").put("time", JSONArray().put(first).put(first + 86400).put(first + 172800))
            val dates = DataCodec.forecast(raw, city, null, 1792144900).days.map { it.date }
            val firstDate = if(zone == "Europe/Berlin") java.time.LocalDate.parse("2026-10-25") else java.time.LocalDate.parse("2026-11-01")
            assertEquals((0L..2L).map { firstDate.plusDays(it).toString() }, dates)
            assertEquals(3, dates.distinct().size)
        }
    }
    @Test fun unitsConvertWithoutChangingStoredValues() {
        assertEquals("32°", temp(0.0, true))
        assertEquals("0°", temp(0.0, false))
        assertEquals("10 mph", speed(16.09344, true))
        assertEquals("—", temp(null))
    }
    @Test fun gridHasFortyNineOrderedPointsAndHandlesDateline() {
        val grid = DataCodec.gridCoordinates(170.0, -5.0, -170.0, 5.0)
        assertEquals(49, grid.points.size)
        assertEquals(170.0, grid.west, 0.0)
        assertEquals(190.0, grid.east, 0.0)
        assertEquals(-5.0, grid.points.first().latitude, 0.0)
        assertEquals(190.0, grid.points.last().longitude, 0.0)
        assertEquals(-170.0, DataCodec.normalizeLongitude(190.0), 0.0)
    }
    @Test fun gridClampsMercatorLatitudeAndKeepsFullWorldSpan() {
        val grid = DataCodec.gridCoordinates(-180.0, -90.0, 180.0, 90.0)
        assertEquals(-85.0, grid.south, 0.0)
        assertEquals(85.0, grid.north, 0.0)
        assertEquals(360.0, grid.east - grid.west, 0.0)
    }
    @Test fun radarOnlyIncludesPastFramesAndSortsThem() {
        val timeline = DataCodec.radar(JSONObject("""{"host":"https://tilecache.rainviewer.com","radar":{
            "past":[{"time":200,"path":"/v2/radar/200"},{"time":100,"path":"/v2/radar/100"}],
            "nowcast":[{"time":300,"path":"/v2/radar/300"}]}}"""), 210)
        assertEquals(listOf(100L, 200L), timeline.frames.map { it.time })
    }
    @Test(expected = java.io.IOException::class) fun invalidForecastDoesNotBecomeFreshWeather() {
        DataCodec.forecast(JSONObject("""{"current":{"temperature_2m":22}}"""), city, null, 1)
    }
}
