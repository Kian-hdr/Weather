package dev.kiankonrad.weather

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant

/** Public-release credits and bundled notices, verified without network or owner-device fixtures. */
@RunWith(AndroidJUnit4::class)
class PublicationTest {
    @get:Rule val compose = createComposeRule()

    @Before fun emulatorOnly() {
        val emulator = Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk_gphone", true) ||
            Build.MODEL.contains("emulator", true) || Build.HARDWARE in listOf("ranchu", "goldfish")
        assumeTrue("Publication UI fixtures run only on the dedicated emulator", emulator)
        compose.mainClock.autoAdvance = false
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    @Test fun settingsCreditsExactCreatorAndOpensAndClosesReadableLicenseDialog() {
        val now = Instant.parse("2026-10-25T09:00:00Z").epochSecond
        val repository = PublicationRepository(now)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WeatherApp(repositoryOverride = repository, displayTimeOverride = now)
            }
        }
        waitFor(hasContentDescription("Settings"))
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor(hasText("Created by Kian Konrad Tajbakhsh", substring = true))
        // performScrollTo repeatedly waits for layout progress while this test deliberately pauses
        // the clock for Sky's infinite animation. Dispatch one scroll and explicitly drive frames.
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, 100_000f)
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("Created by Kian Konrad Tajbakhsh", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Open-source licenses").assertIsDisplayed().performClick()
        waitFor(hasText("MIT License"))
        compose.onNodeWithText("MIT License").assertIsDisplayed()
        compose.onNodeWithText("Copyright (c) 2026 Kian Konrad Tajbakhsh").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("MIT License")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        compose.onNodeWithText("MIT License").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("Open-source licenses").assertIsDisplayed()
    }

    @Test fun releaseBundlesLegalTextsIncludingTheFullMapLibreNativeDependencyNotices() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val required = listOf("MIT.txt", "NOTICE.txt", "Apache-2.0.txt",
            "MapLibre_Android_13.6.1.txt", "Bundled_Dependency_Notices.txt")
        val texts = required.associateWith { name ->
            assets.open("legal/$name").bufferedReader().use { it.readText() }.also {
                assertTrue("Bundled legal/$name must not be empty", it.isNotBlank())
            }
        }
        assertTrue(texts.getValue("MIT.txt").contains("Copyright (c) 2026 Kian Konrad Tajbakhsh"))
        val mapLibre = texts.getValue("MapLibre_Android_13.6.1.txt")
        assertTrue("Ship the complete upstream bundle, not just the top-level BSD license", mapLibre.length > 50_000)
        assertTrue(mapLibre.contains("MapLibre Native Android"))
        assertTrue(mapLibre.contains("BSD 2-Clause License"))
        assertTrue(mapLibre.contains("FreeType"))
        assertTrue(mapLibre.contains("RapidJSON"))
        assertTrue(texts.getValue("Apache-2.0.txt").contains("TERMS AND CONDITIONS"))
    }

    private class PublicationRepository(now: Long) : WeatherRepository {
        private val city = City("publication-fixture", "Publication test city", "", 25.2, 55.3, "Asia/Dubai")
        private val weather = Weather(city,
            Conditions(now, 25.0, 25.0, 1, true, 50.0, 10.0, 15.0, 45.0, 1010.0),
            listOf(Hour(now, 25.0, 1, 0.0, 0.0, 10.0, 15.0, 50.0, 12.0, 1010.0,
                10000.0, 4.0, 25.0, 45.0)), emptyList(), null, now, city.timezone)
        private var savedCities = listOf(city)
        override var imperial = false
        override suspend fun forecast(city: City): Weather = throw IOException("No network in publication test")
        override fun cached(city: City) = weather
        override suspend fun search(query: String): List<City> = emptyList()
        override fun cities() = savedCities
        override fun saveCities(cities: List<City>) { savedCities = cities }
    }
}
