import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "dev.kiankonrad.weather"
 compileSdk = 37
 defaultConfig { applicationId = "dev.kiankonrad.weather"; minSdk = 28; targetSdk = 36; versionCode = providers.gradleProperty("weatherVersionCode").orNull?.toInt() ?: 6; versionName = "1.1.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 signingConfigs { create("personal") { val signing = Properties(); val f = file(System.getProperty("user.home") + "/.config/weather/signing.properties"); if(f.exists()) { f.inputStream().use { signing.load(it) }; storeFile = file(signing.getProperty("storeFile")); storePassword = signing.getProperty("storePassword"); keyAlias = signing.getProperty("keyAlias"); keyPassword = signing.getProperty("keyPassword") } } }
 buildTypes { getByName("debug") { applicationIdSuffix = ".qa" }; getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("personal") } }
 lint { abortOnError = true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2026.09.00"))
 implementation("androidx.activity:activity-compose:1.12.2")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.maplibre.gl:android-sdk:13.6.1")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20250517")
 androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
 debugImplementation("androidx.compose.ui:ui-tooling")
}

layout.buildDirectory.set(file(System.getProperty("user.home") + "/.cache/weather/build/app"))
