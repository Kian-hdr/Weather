package dev.kiankonrad.weather

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit test-only Canvas fixtures, with no forecast, location or provider state. */
@RunWith(AndroidJUnit4::class)
class WeatherArtworkTest {
    @get:Rule val compose = createComposeRule()

    private data class Scene(val name: String, val code: Int, val day: Boolean)

    @Test fun captureSixWeatherScenesOnEmulator() {
        val emulator = Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk_gphone", true) ||
            Build.MODEL.contains("emulator", true) || Build.HARDWARE in listOf("ranchu", "goldfish")
        assumeTrue("Artwork capture runs only on the dedicated emulator", emulator)
        val scenes = listOf(
            Scene("sunny", 0, true), Scene("cloudy", 3, true), Scene("rain", 63, true),
            Scene("snow", 73, true), Scene("night", 0, false), Scene("storm", 95, false)
        )
        val selected = mutableStateOf(scenes.first())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val scene = selected.value
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF102D50))) {
                    Sky(scene.code, scene.day)
                    Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Test artwork fixture · ${scene.name}", color = Color.White, fontSize = 20.sp)
                        WeatherGlyph(scene.code, scene.day, 112)
                    }
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        scenes.forEach { scene ->
            compose.runOnIdle { selected.value = scene }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onNodeWithText("Test artwork fixture · ${scene.name}").assertIsDisplayed()
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Emulator screenshot unavailable" }
            try {
                val output = File(instrumentation.targetContext.cacheDir, "artwork-${scene.name}.png")
                output.outputStream().use { assertTrue("Could not save ${scene.name} artwork capture", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue("Artwork capture is empty", output.length() > 0)
            } finally { bitmap.recycle() }
        }
    }
}
