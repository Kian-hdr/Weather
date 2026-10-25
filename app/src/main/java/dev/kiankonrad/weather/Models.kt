package dev.kiankonrad.weather

import java.time.Instant
import java.time.ZoneId

data class City(val id: String, val name: String, val country: String, val latitude: Double, val longitude: Double, val timezone: String = "UTC")
data class Hour(val time: Long, val temperature: Double?, val code: Int?, val precipitationProbability: Double?, val precipitation: Double?, val wind: Double?, val gusts: Double?, val humidity: Double?, val dewPoint: Double?, val pressure: Double?, val visibility: Double?, val uv: Double?, val feelsLike: Double?, val windDirection: Double?)
data class Day(val date: String, val code: Int?, val high: Double?, val low: Double?, val sunrise: Long?, val sunset: Long?, val uv: Double?, val precipitation: Double?, val precipitationProbability: Double?)
data class Conditions(val time: Long, val temperature: Double?, val feelsLike: Double?, val code: Int?, val isDay: Boolean, val humidity: Double?, val wind: Double?, val gusts: Double?, val windDirection: Double?, val pressure: Double?)
data class AirQuality(val time: Long, val europeanAqi: Double?, val usAqi: Double?, val pm25: Double?, val pm10: Double?)
data class Weather(val city: City, val current: Conditions, val hours: List<Hour>, val days: List<Day>, val air: AirQuality?, val fetchedAt: Long, val timezone: String)
data class RadarFrame(val time: Long, val path: String)
data class RadarTimeline(val host: String, val frames: List<RadarFrame>, val fetchedAt: Long)
data class TemperaturePoint(val latitude: Double, val longitude: Double, val temperature: Double?, val time: Long)
data class TemperatureGrid(val west: Double, val south: Double, val east: Double, val north: Double, val points: List<TemperaturePoint>, val fetchedAt: Long)
interface WeatherRepository {
 suspend fun forecast(city: City): Weather
 fun cached(city: City): Weather?
 suspend fun search(query: String): List<City>
 fun cities(): List<City>
 fun saveCities(cities: List<City>)
 var imperial: Boolean
}
interface MapRepository {
 suspend fun radar(): RadarTimeline
 suspend fun temperatures(west: Double, south: Double, east: Double, north: Double): TemperatureGrid
}
fun Double?.display(decimals: Int = 0): String = this?.let { String.format(java.util.Locale.US, "%.${decimals}f", it) } ?: "—"
fun temp(value: Double?, imperial: Boolean = false): String = (value?.let { if(imperial) it * 9 / 5 + 32 else it }).display() + if(value == null) "" else "°"
fun speed(value: Double?, imperial: Boolean): String = (value?.let { if(imperial) it / 1.609344 else it }).display() + if(imperial) " mph" else " km/h"
object DisplayClock {var pattern: String = "HH:mm"}
fun localTime(epoch: Long, zone: String, pattern: String = DisplayClock.pattern): String = java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US).format(Instant.ofEpochSecond(epoch).atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC"))))
fun condition(code: Int?): String = when(code) { 0 -> "Clear sky"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45,48 -> "Fog"; 51,53,55 -> "Drizzle"; 56,57 -> "Freezing drizzle"; 61,63,65 -> "Rain"; 66,67 -> "Freezing rain"; 71,73,75,77 -> "Snow"; 80,81,82 -> "Rain showers"; 85,86 -> "Snow showers"; 95,96,99 -> "Thunderstorms"; else -> "Conditions unavailable" }
