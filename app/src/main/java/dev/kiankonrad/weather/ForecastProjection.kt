package dev.kiankonrad.weather

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Describes the data used for a display instant, not the device's connectivity. */
enum class ConditionSource { CURRENT, FORECAST, UNAVAILABLE }

/** Evaluate this on the original provider snapshot, before applying [atTime]. */
fun Weather.conditionSourceAt(now: Long): ConditionSource = when {
    now - current.time in 0L..900L -> ConditionSource.CURRENT
    forecastHourAt(now) != null -> ConditionSource.FORECAST
    else -> ConditionSource.UNAVAILABLE
}

/**
 * Project a saved provider response onto the display clock without making it look freshly fetched.
 * Interpolation stays between adjacent hourly samples; it never bridges a missing hour or extends
 * the forecast beyond its final sample. Codes use the containing hour, not a numeric interpolation.
 */
fun Weather.atTime(now: Long): Weather {
    val source = conditionSourceAt(now)
    val hour = if (source == ConditionSource.FORECAST) forecastHourAt(now) else null
    val projected = when (source) {
        ConditionSource.CURRENT -> current.copy(time = now)
        ConditionSource.FORECAST -> Conditions(
            now, hour?.temperature, hour?.feelsLike, hour?.code, daylightAt(now),
            hour?.humidity, hour?.wind, hour?.gusts, hour?.windDirection, hour?.pressure
        )
        ConditionSource.UNAVAILABLE -> Conditions(
            now, null, null, null, daylightAt(now), null, null, null, null, null
        )
    }
    val today = Instant.ofEpochSecond(now).atZone(displayZone()).toLocalDate()
    return copy(
        current = projected,
        days = days.filter { runCatching { !LocalDate.parse(it.date).isBefore(today) }.getOrDefault(false) },
        air = air?.takeIf { now - it.time in 0L..3600L }
    )
}

/** A modeled hourly value at this exact instant, or null when the cached horizon does not cover it. */
fun Weather.forecastHourAt(now: Long): Hour? {
    val ordered = hours.sortedBy { it.time }.distinctBy { it.time }
    ordered.firstOrNull { it.time == now }?.let { return it }
    val rightIndex = ordered.indexOfFirst { it.time > now }
    if (rightIndex <= 0) return null
    val left = ordered[rightIndex - 1]
    val right = ordered[rightIndex]
    val span = right.time - left.time
    if (span !in 1L..3600L) return null
    val fraction = (now - left.time).toDouble() / span
    fun blend(a: Double?, b: Double?): Double? =
        if (a != null && b != null && a.isFinite() && b.isFinite()) a + (b - a) * fraction else null
    val direction = if (left.windDirection?.isFinite() == true && right.windDirection?.isFinite() == true) {
        val a = ((left.windDirection % 360.0) + 360.0) % 360.0
        val b = ((right.windDirection % 360.0) + 360.0) % 360.0
        val delta = ((b - a + 540.0) % 360.0) - 180.0
        ((a + delta * fraction) % 360.0 + 360.0) % 360.0
    } else null
    return Hour(
        now, blend(left.temperature, right.temperature), left.code,
        blend(left.precipitationProbability, right.precipitationProbability),
        blend(left.precipitation, right.precipitation), blend(left.wind, right.wind),
        blend(left.gusts, right.gusts), blend(left.humidity, right.humidity),
        blend(left.dewPoint, right.dewPoint), blend(left.pressure, right.pressure),
        blend(left.visibility, right.visibility), blend(left.uv, right.uv),
        blend(left.feelsLike, right.feelsLike), direction
    )
}

private fun Weather.displayZone(): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

private fun Weather.daylightAt(now: Long): Boolean {
    val local = Instant.ofEpochSecond(now).atZone(displayZone())
    val day = days.firstOrNull { it.date == local.toLocalDate().toString() }
    if (day?.sunrise != null && day.sunset != null && day.sunset > day.sunrise) {
        return now >= day.sunrise && now < day.sunset
    }
    // This only chooses background artwork when solar data is missing (including polar locations).
    // It is not presented as a measured sunrise/sunset or a fresh provider is_day value.
    return local.hour in 6 until 18
}
