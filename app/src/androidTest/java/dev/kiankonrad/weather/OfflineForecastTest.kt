package dev.kiankonrad.weather

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Emulator-only clock-driven journeys; no phone clock, disk cache, location, or network changes. */
@RunWith(AndroidJUnit4::class)
class OfflineForecastTest {
    @get:Rule val compose = createComposeRule()

    private val start = Instant.parse("2026-10-25T09:00:00Z").epochSecond // Dubai 13:00
    private val clock = mutableStateOf(start)
    private val city = City("offline-fixture", "Offline test city", "", 25.2, 55.3, "Asia/Dubai")
    private lateinit var repository: OfflineRepository

    @Before fun emulatorOnly() {
        val emulator = Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk_gphone", true) ||
            Build.MODEL.contains("emulator", true) || Build.HARDWARE in listOf("ranchu", "goldfish")
        assumeTrue("Never seed forecast fixtures or test clocks on the owner's phone", emulator)
        compose.mainClock.autoAdvance = false
        repository = OfflineRepository(fixture())
    }

    private fun fixture(): Weather {
        val hours = (0L..14L).map { index ->
            Hour(start + index * 3600, 30.0 - index, if (index < 3) 1 else 3,
                0.0, 0.0, 10.0, 15.0, 50.0, 12.0, 1010.0, 10000.0, 4.0,
                29.0 - index, 45.0)
        }
        fun epoch(text: String) = Instant.parse(text).epochSecond
        return Weather(city, Conditions(start, 30.0, 29.0, 1, true, 50.0, 10.0, 15.0, 45.0, 1010.0),
            hours, listOf(
                Day("2026-10-25", 1, 34.0, 18.0, epoch("2026-10-25T02:20:00Z"),
                    epoch("2026-10-25T13:40:00Z"), 7.0, 0.0, 0.0),
                Day("2026-10-26", 3, 22.0, 12.0, epoch("2026-10-26T02:20:00Z"),
                    epoch("2026-10-26T13:40:00Z"), 6.0, 0.0, 0.0)
            ), null, start, "Asia/Dubai")
    }

    private fun launch(at: Long = start) {
        clock.value = at
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WeatherApp(repositoryOverride = repository, displayTimeOverride = clock.value)
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun moveClock(to: Long) {
        compose.runOnIdle { clock.value = to }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun assertTemperature(degrees: String) {
        waitFor(hasContentDescription("$degrees Celsius"))
        compose.onNodeWithContentDescription("$degrees Celsius").performScrollTo().assertIsDisplayed()
    }

    private fun assertEstimate(at: Long) {
        val text = "Estimated now · ${localTime(at, "Asia/Dubai")}"
        waitFor(hasText(text))
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun assertNowTemperature(degrees: String) {
        waitFor(hasText("Now"))
        compose.onNodeWithText("Now", useUnmergedTree = true).performScrollTo().onParent()
            .assert(hasAnyDescendant(hasText(degrees)))
    }

    @Test fun savedForecastAdvancesFromOneToFourAndSurvivesSettingsNavigation() {
        launch()
        assertTemperature("30°")
        moveClock(start + 3 * 3600)
        assertTemperature("27°")
        assertEstimate(start + 3 * 3600)
        assertNowTemperature("27°")

        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasContentDescription("Use imperial units"))
        moveClock(start + 4 * 3600)
        compose.onNodeWithContentDescription("Back").performClick()
        assertTemperature("26°")
        assertEstimate(start + 4 * 3600)
        assertNowTemperature("26°")
        assertEquals(start, repository.snapshot.fetchedAt)
    }

    @Test fun midnightChangesTheCurrentDayAndItsHighAndLow() {
        launch()
        waitFor(hasText("H:34°   L:18°"))
        moveClock(Instant.parse("2026-10-25T20:00:00Z").epochSecond) // Dubai midnight
        assertTemperature("19°")
        waitFor(hasText("H:22°   L:12°"))
        compose.onNodeWithText("H:22°   L:12°").assertIsDisplayed()
        compose.onNodeWithText("H:34°   L:18°").assertDoesNotExist()
        compose.onNodeWithText("Today").performScrollTo().assertIsDisplayed()
        assertEquals("2026-10-25", repository.snapshot.days.first().date)
    }

    @Test fun detailLeftOpenAcrossMidnightSwitchesItsDateHeaderAndChart() {
        launch(start + 10 * 3600) // Dubai 23:00, temperature 20 degrees.
        assertTemperature("20°")
        compose.onNodeWithText("Now").performScrollTo().performClick()
        waitFor(hasText("Temperature"))
        waitFor(hasText("2026-10-25 · Offline test city"))
        compose.onNodeWithText("2026-10-25 · Offline test city").assertExists()

        moveClock(Instant.parse("2026-10-25T20:00:00Z").epochSecond) // Dubai midnight
        waitFor(hasText("2026-10-26 · Offline test city"))
        compose.onNodeWithText("2026-10-26 · Offline test city").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2026-10-25 · Offline test city").assertDoesNotExist()
        compose.onNodeWithText("10-26").assertIsSelected()
        compose.onNodeWithText("Today’s high 22°. Tap to explore the forecast.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Forecast chart. Minimum 16.0, maximum 19.0. Hourly values below."
        ).performScrollTo().assertIsDisplayed()
    }

    @Test fun expiredForecastDoesNotKeepTheLastTemperatureAsCurrent() {
        launch(start + 14 * 3600)
        assertTemperature("16°")
        moveClock(start + 14 * 3600 + 1)
        assertTemperature("—")
        val message = "Saved forecast doesn’t cover this time. Connect to update weather."
        waitFor(hasText(message))
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Now").assertDoesNotExist()
    }

    @Test fun failedRefreshRetainsTheTimeAppropriateForecast() {
        launch(start + 3 * 3600)
        val message = "Couldn’t refresh. Using saved forecast."
        waitFor(hasText(message))
        assertTemperature("27°")
        assertEstimate(start + 3 * 3600)
        val requests = repository.requests.get()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeByFrame()
            repository.requests.get() > requests
        }
        assertTemperature("27°")
        assertNowTemperature("27°")
        assertEquals(start, repository.snapshot.fetchedAt)
    }

    private class OfflineRepository(val snapshot: Weather) : WeatherRepository {
        val requests = AtomicInteger()
        private var savedCities = listOf(snapshot.city)
        override var imperial = false
        override suspend fun forecast(city: City): Weather {
            requests.incrementAndGet()
            throw IOException("Offline test repository: no network request is made")
        }
        override fun cached(city: City) = snapshot
        override suspend fun search(query: String): List<City> = emptyList()
        override fun cities() = savedCities
        override fun saveCities(cities: List<City>) { savedCities = cities }
    }
}
