package dev.kiankonrad.weather

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only acceptance journeys against real providers. Tests ending in LiveProviders require
 * internet and provider availability; an unavailable provider is a failed acceptance gate, not a pass.
 * These tests never request location or notification permissions and never run fixtures on a phone.
 */
@RunWith(AndroidJUnit4::class)
class WeatherFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var repository: OpenWeatherRepository
    private var originalCities: List<City> = emptyList()
    private var originalImperial = false
    private var initialized = false
    private val berlin = City("ui-test-berlin", "Berlin", "Germany", 52.52, 13.41, "Europe/Berlin")

    @Before fun setUp() {
        val emulator = Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk_gphone", true) ||
            Build.MODEL.contains("emulator", true) || Build.HARDWARE in listOf("ranchu", "goldfish")
        assumeTrue("Emulator-only test: do not seed test locations on the owner's phone", emulator)
        repository = OpenWeatherRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        originalCities = repository.cities()
        originalImperial = repository.imperial
        initialized = true
        compose.mainClock.autoAdvance = false // Sky has an infinite animation; acceptance must not wait for it.
    }

    @After fun tearDown() {
        scenario?.close()
        scenario = null
        if (initialized) {
            repository.saveCities(originalCities)
            repository.imperial = originalImperial
        }
    }

    private fun launch(cities: List<City> = listOf(berlin), imperial: Boolean = false) {
        repository.saveCities(cities)
        repository.imperial = imperial
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.mainClock.advanceTimeByFrame()
    }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 60_000) {
        compose.waitUntil(timeoutMillis = timeout) {
            compose.mainClock.advanceTimeByFrame()
            exists(matcher)
        }
    }

    private fun waitForecast() = waitFor(hasContentDescription("Celsius", substring = true))

    @Test fun emptyFirstRunKeepsManualSearchAvailableWithoutLocationPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .map { context.checkSelfPermission(it) }
        launch(emptyList())
        waitFor(hasText("Use my location"), 10_000)
        compose.onNodeWithText("Use my location").assertIsDisplayed()
        compose.onNodeWithText("Location access is optional.", substring = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertTrue(repository.cities().isEmpty())
        val after = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .map { context.checkSelfPermission(it) }
        assertEquals("Opening Weather must not request or change location permission", before, after)
    }

    @Test fun manualCitySearchAndSavedCitySurviveRelaunchLiveProviders() {
        launch(emptyList())
        waitFor(hasSetTextAction(), 10_000)
        compose.onNode(hasSetTextAction()).performTextInput("Berlin")
        // Match the clickable search-result card, not the text field whose current text is Berlin.
        val berlinResult = hasText("Berlin") and hasClickAction() and !hasSetTextAction()
        waitFor(berlinResult)
        compose.onAllNodes(berlinResult).onFirst().performClick()
        waitForecast()
        assertEquals("Berlin", repository.cities().single().name)
        assertFalse(repository.cities().single().id == "ui-test-berlin")
        val saved = repository.cities().single()
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForecast()
        assertEquals(saved, repository.cities().single())
        compose.onNodeWithContentDescription("Saved cities and search").performClick()
        waitFor(hasText("Berlin") and hasClickAction())
        compose.onNodeWithContentDescription("Remove Berlin").assertIsDisplayed()
    }

    @Test fun unitPreferencePersistsAcrossRelaunchLiveProviders() {
        launch()
        waitForecast()
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasContentDescription("Use imperial units"), 10_000)
        compose.onNodeWithContentDescription("Use imperial units").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(repository.imperial)
        compose.onNodeWithText("Fahrenheit · mph · inches").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasContentDescription("Fahrenheit", substring = true), 10_000)
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasContentDescription("Fahrenheit", substring = true))
        assertTrue(repository.imperial)
        assertEquals(listOf(berlin), repository.cities())
    }

    @Test fun detailNavigationAndBothMapLayersLoadLiveProviders() {
        launch()
        waitForecast()
        compose.onNodeWithText("Now").performScrollTo().performClick()
        waitFor(hasText("Temperature"), 10_000)
        compose.onNodeWithText("Temperature").assertIsDisplayed()
        waitFor(hasContentDescription("Forecast chart.", substring = true), 10_000)
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasContentDescription("Weather maps"), 10_000)
        compose.onNodeWithContentDescription("Weather maps").performClick()
        waitFor(hasText("Recent precipitation radar"), 15_000)
        compose.onNodeWithText("Rainfall").assertIsDisplayed()
        compose.onNodeWithText("Temperature").assertIsDisplayed()
        waitFor(hasContentDescription("Radar timeline"))
        compose.onNodeWithContentDescription("Radar timeline").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play recent radar").performClick()
        waitFor(hasContentDescription("Pause radar"), 10_000)
        compose.onNodeWithContentDescription("Pause radar").performClick()
        compose.onNodeWithText("Temperature").performClick()
        waitFor(hasText("Interpolated model temperature"), 10_000)
        waitFor(hasText("Valid ", substring = true))
        compose.onNodeWithText("Interpolated model temperature").assertIsDisplayed()
        compose.onNodeWithText("Open-Meteo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(hasContentDescription("Saved cities and search"), 10_000)
        assertEquals(listOf(berlin), repository.cities())
    }
}
