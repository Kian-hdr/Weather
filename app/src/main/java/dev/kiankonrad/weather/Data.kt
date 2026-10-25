package dev.kiankonrad.weather

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Direct, personal-use provider access. All stored data remains in app-private storage. */
class OpenWeatherRepository(context: Context) : WeatherRepository, MapRepository {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, "weather-cache").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(app.cacheDir, "provider-http"), 24L * 1024 * 1024))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Weather/1.1.1 (https://github.com/Kian-hdr/Weather; dev.kiankonrad.weather)")
                .build())
        }.build()

    override var imperial: Boolean
        get() = preferences.getBoolean("imperial", false)
        set(value) { preferences.edit().putBoolean("imperial", value).apply() }

    override fun cities(): List<City> = runCatching {
        DataCodec.cities(JSONArray(preferences.getString("cities", "[]")))
    }.getOrDefault(emptyList())

    override fun saveCities(cities: List<City>) {
        val unique = cities.distinctBy { it.id }
        preferences.edit().putString("cities", JSONArray().apply {
            unique.forEach { put(DataCodec.cityJson(it)) }
        }.toString()).apply()
        val retained = unique.map { "forecast-${cacheKey(it)}.json" }.toSet()
        directory.listFiles { file -> file.name.startsWith("forecast-") && file.name.endsWith(".json") }
            ?.filter { it.name !in retained }?.forEach { it.delete() }
    }

    override fun cached(city: City): Weather? = runCatching {
        val envelope = JSONObject(read("forecast-${cacheKey(city)}.json") ?: return null)
        DataCodec.cachedForecast(envelope, city)
    }.getOrNull()

    override suspend fun forecast(city: City): Weather = withContext(Dispatchers.IO) {
        coroutineScope {
            val air = async {
                try {
                    JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality", mapOf(
                        "latitude" to city.latitude.toString(), "longitude" to city.longitude.toString(),
                        "current" to "european_aqi,us_aqi,pm2_5,pm10", "timeformat" to "unixtime"
                    )))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            val raw = JSONObject(get("https://api.open-meteo.com/v1/forecast", mapOf(
                "latitude" to city.latitude.toString(), "longitude" to city.longitude.toString(),
                "current" to "temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m,pressure_msl",
                "hourly" to "temperature_2m,weather_code,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m,relative_humidity_2m,dew_point_2m,pressure_msl,visibility,uv_index,apparent_temperature,wind_direction_10m",
                "daily" to "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,precipitation_sum,precipitation_probability_max",
                "forecast_days" to "10", "timezone" to "auto", "timeformat" to "unixtime"
            )))
            val airJson = air.await()
            val now = Instant.now().epochSecond
            val result = DataCodec.forecast(raw, city, airJson, now)
            // Only a successfully parsed forecast may replace last-good data.
            write("forecast-${cacheKey(city)}.json", JSONObject().put("city", DataCodec.cityJson(city))
                .put("forecast", raw).put("air", airJson ?: JSONObject.NULL).put("fetchedAt", now).toString())
            result
        }
    }

    override suspend fun search(query: String): List<City> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.length < 2) return@withContext emptyList()
        val result = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search", mapOf(
            "name" to text.take(100), "count" to "12", "language" to "en", "format" to "json"
        )))
        val rows = result.optJSONArray("results") ?: return@withContext emptyList()
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val lat = row.number("latitude") ?: return@mapNotNull null
            val lon = row.number("longitude") ?: return@mapNotNull null
            val country = listOf(row.optString("admin1"), row.optString("country"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            City(row.optString("id", "${lat},${lon}"), row.getString("name"), country,
                lat, lon, row.optString("timezone", "UTC"))
        }
    }

    override suspend fun radar(): RadarTimeline = withContext(Dispatchers.IO) {
        val now = Instant.now().epochSecond
        val saved = runCatching { read("radar.json")?.let { JSONObject(it) } }.getOrNull()
        if (saved != null && now - saved.optLong("fetchedAt") in 0L until 300L) {
            return@withContext DataCodec.radar(saved.getJSONObject("data"), saved.getLong("fetchedAt"))
        }
        try {
            val data = JSONObject(get("https://api.rainviewer.com/public/weather-maps.json"))
            val result = DataCodec.radar(data, now)
            write("radar.json", JSONObject().put("data", data).put("fetchedAt", now).toString())
            result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Recent cached frames may still exist; callers must show their actual timestamp.
            if (saved != null && now - saved.optLong("fetchedAt") in 0L until 2L * 60 * 60) {
                DataCodec.radar(saved.getJSONObject("data"), saved.getLong("fetchedAt"))
            } else throw IOException("Rain radar is unavailable. Try again shortly.", e)
        }
    }

    override suspend fun temperatures(west: Double, south: Double, east: Double, north: Double): TemperatureGrid = withContext(Dispatchers.IO) {
        val area = DataCodec.gridCoordinates(west, south, east, north)
        val key = "temperature-" + listOf(area.west, area.south, area.east, area.north)
            .joinToString("_") { (it * 1000).roundToInt().toString() } + ".json"
        val now = Instant.now().epochSecond
        val saved = runCatching { read(key)?.let { DataCodec.grid(JSONObject(it)) } }.getOrNull()
        if (saved != null && now - saved.fetchedAt in 0L until 30L * 60) return@withContext saved
        val coordinates = area.points
        val raw = get("https://api.open-meteo.com/v1/forecast", mapOf(
            "latitude" to coordinates.joinToString(",") { String.format(Locale.US, "%.5f", it.latitude) },
            "longitude" to coordinates.joinToString(",") { String.format(Locale.US, "%.5f", DataCodec.normalizeLongitude(it.longitude)) },
            "current" to "temperature_2m", "forecast_days" to "1", "timeformat" to "unixtime", "cell_selection" to "nearest"
        ))
        val rows = JSONArray(raw)
        val result = area.copy(points = coordinates.mapIndexed { index, coordinate ->
            val current = rows.optJSONObject(index)?.optJSONObject("current")
            val time = current?.longNumber("time") ?: 0
            coordinate.copy(temperature = if (time > 0) current?.number("temperature_2m") else null, time = time)
        }, fetchedAt = now)
        if (result.points.none { it.temperature != null && it.time > 0 }) {
            throw IOException("Temperature data is unavailable for this area. Try again.")
        }
        write(key, DataCodec.gridJson(result).toString())
        // Keep a bounded private disk cache; never accumulate an unlimited travel history.
        directory.listFiles { f -> f.name.startsWith("temperature-") }?.sortedByDescending { it.lastModified() }
            ?.drop(12)?.forEach { it.delete() }
        result
    }

    private fun cacheKey(city: City): String = "${city.latitude.toBits()}_${city.longitude.toBits()}"
    private fun get(endpoint: String, params: Map<String, String> = emptyMap()): String {
        val url = endpoint.toHttpUrl().newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException(when (response.code) {
                429 -> "The weather provider is busy. Please try again later."
                else -> "Weather data could not be loaded (${response.code})."
            })
            return response.body?.string() ?: throw IOException("The weather provider returned no data.")
        }
    }
    private fun read(name: String): String? = runCatching {
        AtomicFile(File(directory, name)).openRead().bufferedReader().use { it.readText() }
    }.getOrNull()
    private fun write(name: String, content: String) {
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try { stream.write(content.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

internal fun JSONObject.number(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
internal fun JSONObject.longNumber(key: String): Long? = number(key)?.toLong()
private fun JSONObject.arrayNumber(key: String, index: Int): Double? = optJSONArray(key)?.let { a ->
    if (index < a.length() && !a.isNull(index)) a.optDouble(index).takeIf { it.isFinite() } else null
}

/** Pure parsers are kept separate so missing-value, timezone and persistence behavior can be tested. */
internal object DataCodec {
    fun cityJson(city: City): JSONObject = JSONObject().put("id", city.id).put("name", city.name).put("country", city.country)
        .put("latitude", city.latitude).put("longitude", city.longitude).put("timezone", city.timezone)
    fun city(json: JSONObject): City = City(json.getString("id"), json.getString("name"), json.optString("country"),
        json.getDouble("latitude"), json.getDouble("longitude"), json.optString("timezone", "UTC"))
    fun cities(json: JSONArray): List<City> = (0 until json.length()).map { city(json.getJSONObject(it)) }

    // Cached payloads are keyed by coordinates, but display identity belongs to the current request.
    fun cachedForecast(envelope: JSONObject, requestedCity: City): Weather = forecast(
        envelope.getJSONObject("forecast"), requestedCity, envelope.optJSONObject("air"), envelope.getLong("fetchedAt")
    )

    fun forecast(raw: JSONObject, city: City, airRaw: JSONObject?, fetchedAt: Long): Weather {
        val zone = raw.optString("timezone", city.timezone).takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: "UTC"
        val c = raw.getJSONObject("current")
        val currentTime = c.longNumber("time")?.takeIf { it > 0 } ?: throw IOException("The forecast has no valid update time.")
        val current = Conditions(currentTime, c.number("temperature_2m"), c.number("apparent_temperature"),
            c.number("weather_code")?.toInt(), c.optInt("is_day", 1) == 1, c.number("relative_humidity_2m"),
            c.number("wind_speed_10m"), c.number("wind_gusts_10m"), c.number("wind_direction_10m"), c.number("pressure_msl"))
        val h = raw.optJSONObject("hourly") ?: JSONObject()
        val times = h.optJSONArray("time") ?: JSONArray()
        val hours = (0 until times.length()).mapNotNull { i ->
            val time = h.arrayNumber("time", i)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            Hour(time, h.arrayNumber("temperature_2m", i), h.arrayNumber("weather_code", i)?.toInt(),
                h.arrayNumber("precipitation_probability", i), h.arrayNumber("precipitation", i),
                h.arrayNumber("wind_speed_10m", i), h.arrayNumber("wind_gusts_10m", i),
                h.arrayNumber("relative_humidity_2m", i), h.arrayNumber("dew_point_2m", i),
                h.arrayNumber("pressure_msl", i), h.arrayNumber("visibility", i), h.arrayNumber("uv_index", i),
                h.arrayNumber("apparent_temperature", i), h.arrayNumber("wind_direction_10m", i))
        }
        val d = raw.optJSONObject("daily") ?: JSONObject()
        val days = (0 until (d.optJSONArray("time")?.length() ?: 0)).mapNotNull { i ->
            val time = d.arrayNumber("time", i)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            // Daily timestamps encode calendar buckets with the provider's fixed response offset.
            // Hourly instants still use the IANA zone so repeated/missing DST hours remain correct.
            val date = raw.longNumber("utc_offset_seconds")?.let { offset ->
                Instant.ofEpochSecond(time + offset).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
            } ?: Instant.ofEpochSecond(time).atZone(ZoneId.of(zone)).toLocalDate().toString()
            Day(date, d.arrayNumber("weather_code", i)?.toInt(), d.arrayNumber("temperature_2m_max", i),
                d.arrayNumber("temperature_2m_min", i), d.arrayNumber("sunrise", i)?.toLong()?.takeIf { it > 0 }, d.arrayNumber("sunset", i)?.toLong()?.takeIf { it > 0 },
                d.arrayNumber("uv_index_max", i), d.arrayNumber("precipitation_sum", i), d.arrayNumber("precipitation_probability_max", i))
        }
        val aq = airRaw?.optJSONObject("current")?.let { a ->
            a.longNumber("time")?.takeIf { it > 0 }?.let { time ->
                AirQuality(time, a.number("european_aqi"), a.number("us_aqi"), a.number("pm2_5"), a.number("pm10"))
            }
        }
        return Weather(city.copy(timezone = zone), current, hours, days, aq, fetchedAt, zone)
    }

    fun radar(raw: JSONObject, fetchedAt: Long): RadarTimeline {
        val host = raw.getString("host")
        require(host.startsWith("https://")) { "Radar requires a secure tile source." }
        val array = raw.optJSONObject("radar")?.optJSONArray("past") ?: JSONArray()
        val frames = (0 until array.length()).mapNotNull { i ->
            val frame = array.optJSONObject(i) ?: return@mapNotNull null
            val path = frame.optString("path")
            val time = frame.longNumber("time")?.takeIf { it > 0 } ?: return@mapNotNull null
            if (!path.startsWith("/v2/radar/")) return@mapNotNull null
            RadarFrame(time, path)
        }.distinctBy { it.time }.sortedBy { it.time }
        if (frames.isEmpty()) throw IOException("Rain radar has no recent frames. Try again later.")
        return RadarTimeline(host.trimEnd('/'), frames, fetchedAt)
    }

    fun normalizeLongitude(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    fun gridCoordinates(west: Double, south: Double, east: Double, north: Double): TemperatureGrid {
        require(listOf(west, south, east, north).all { it.isFinite() }) { "Invalid map area." }
        val w = normalizeLongitude(west)
        val rawSpan = east - west
        val span = if (kotlin.math.abs(rawSpan) >= 360) 360.0 else ((rawSpan % 360.0) + 360.0) % 360.0
        val s = minOf(south, north).coerceIn(-85.0, 85.0)
        val n = maxOf(south, north).coerceIn(-85.0, 85.0)
        require(span > 0.00001 && n - s > 0.00001) { "Zoom out slightly to load this area." }
        val points = (0..6).flatMap { row -> (0..6).map { column ->
            TemperaturePoint(s + (n - s) * row / 6.0, w + span * column / 6.0, null, 0)
        } }
        return TemperatureGrid(w, s, w + span, n, points, 0)
    }
    fun gridJson(grid: TemperatureGrid): JSONObject = JSONObject().put("west", grid.west).put("south", grid.south)
        .put("east", grid.east).put("north", grid.north).put("fetchedAt", grid.fetchedAt).put("points", JSONArray().apply {
            grid.points.forEach { p -> put(JSONObject().put("lat", p.latitude).put("lon", p.longitude)
                .put("temperature", p.temperature ?: JSONObject.NULL).put("time", p.time)) }
        })
    fun grid(raw: JSONObject): TemperatureGrid {
        val points = raw.getJSONArray("points")
        return TemperatureGrid(raw.getDouble("west"), raw.getDouble("south"), raw.getDouble("east"), raw.getDouble("north"),
            (0 until points.length()).map { i -> points.getJSONObject(i).let { p ->
                TemperaturePoint(p.getDouble("lat"), p.getDouble("lon"), p.number("temperature"), p.getLong("time"))
            } }, raw.getLong("fetchedAt"))
    }
}
