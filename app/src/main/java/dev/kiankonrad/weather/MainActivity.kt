package dev.kiankonrad.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.math.*

private val Ink = Color.White
private val Muted = Color(0xFFE0EBFA)
private val Panel = Color(0xEE193B60)
private val Accent = Color(0xFFB6DEFF)

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
  setContent {
   MaterialTheme(colorScheme = darkColorScheme(primary=Accent, onPrimary=Color(0xFF062642), surface=Color(0xFF123459), onSurface=Ink, onSurfaceVariant=Muted, background=Color(0xFF102D50))) {
    Surface(Modifier.fillMaxSize(),color=Color(0xFF102D50),contentColor=Ink) {WeatherApp()}
   }
  }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherApp(repositoryOverride: WeatherRepository? = null, displayTimeOverride: Long? = null) {
 val context=LocalContext.current
 SideEffect {DisplayClock.pattern=if(android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"}
 val repository=repositoryOverride ?: remember { OpenWeatherRepository(context.applicationContext) }
 val now=displayTimeOverride ?: rememberWeatherTime()
 var cities by remember { mutableStateOf(repository.cities()) }
 var imperial by remember { mutableStateOf(repository.imperial) }
 var screen by rememberSaveable { mutableStateOf("weather") }
 var detail by remember { mutableStateOf<Metric?>(null) }
 val weatherByCity=remember {mutableStateMapOf<String,Weather>()}
 var currentIndex by remember { mutableIntStateOf(0) }
 var locationMessage by remember { mutableStateOf<String?>(null) }
 var locating by remember { mutableStateOf(false) }
 val lastWeather=cities.getOrNull(currentIndex)?.let {weatherByCity[it.id]}?.atTime(now)
 val scope=rememberCoroutineScope()
 val pager=rememberPagerState { cities.size }
 LaunchedEffect(pager.currentPage) { currentIndex=pager.currentPage }
 fun add(city: City) {
  val old=cities.indexOfFirst { it.id==city.id }
  if(old<0) cities=cities+city else cities=cities.mapIndexed {i,c ->if(i==old)city else c}
  repository.saveCities(cities)
  screen="weather"
  scope.launch { pager.scrollToPage(if(old>=0) old else cities.lastIndex) }
 }
 fun locate() {
  locating=true; locationMessage=null
  scope.launch {
   val result=try {withTimeoutOrNull(18000) { findLocation(context) }} catch(e: CancellationException) {throw e} catch(e: Exception) {null}
   locating=false
   if(result==null) locationMessage="Location is unavailable. Check location services or search for a city."
   else add(City("current-location", "My Location", "", result.first, result.second))
  }
 }
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
  if(results.values.any { it }) locate() else locationMessage="Location access is off. You can still search for any city."
 }
 val onLocation: ()->Unit = {
  if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) locate()
  else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
 }
 BackHandler(screen!="weather" || detail!=null) { if(detail!=null) detail=null else screen="weather" }
 Box(Modifier.fillMaxSize().background(Color(0xFF102D50))) {
  Sky(lastWeather?.current?.code,lastWeather?.current?.isDay ?: true)
  when {
   detail!=null && lastWeather!=null -> MetricScreen(detail!!,lastWeather!!,imperial) { detail=null }
   screen=="map" && cities.isNotEmpty() -> {
    val selected=cities.getOrElse(currentIndex){cities.first()}
    val resolved=lastWeather ?: repository.cached(selected)
    WeatherMap(resolved?.city?.copy(timezone=resolved.timezone) ?: selected,repository as? MapRepository ?: remember { OpenWeatherRepository(context.applicationContext) },imperial) {screen="weather"}
   }
   screen=="cities" || cities.isEmpty() -> CityScreen(cities, repository, locationMessage, locating, onLocation, ::add, {city ->
    cities=cities.filter {it.id!=city.id}; repository.saveCities(cities)
   }, { if(cities.isNotEmpty()) screen="weather" })
   screen=="settings" -> SettingsScreen(imperial,{imperial=it;repository.imperial=it}) {screen="weather"}
   else -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
    HorizontalPager(state=pager, modifier=Modifier.weight(1f), key={cities[it].id}) {page ->
     ForecastPage(cities[page],repository,imperial,now,showMapPreview=repositoryOverride==null,onWeather={weatherByCity[cityKey(it)]=it},onMetric={weather,metric ->weatherByCity[cityKey(weather)]=weather;detail=metric}, onMap={screen="map"})
    }
    Row(Modifier.fillMaxWidth().background(Color(0xD9122E50)).navigationBarsPadding().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
     IconButton(onClick={screen="map"}) {Icon(Icons.Outlined.Map,"Weather maps")}
     Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
      Spacer(Modifier.weight(1f))
      cities.forEachIndexed {i,city -> Box(Modifier.size(8.dp).clip(CircleShape).background(if(i==pager.currentPage) Ink else Ink.copy(alpha=.35f)).semantics {contentDescription="${city.name}${if(i==pager.currentPage) ", selected" else ""}"}) }
      Spacer(Modifier.weight(1f))
     }
     IconButton(onClick={screen="settings"}) {Icon(Icons.Outlined.Tune,"Settings")}
     IconButton(onClick={screen="cities"}) {Icon(Icons.Outlined.FormatListBulleted,"Saved cities and search")}
    }
   }
  }
  Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Color(0x99112340)))
  Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(Color(0x99112340)))
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForecastPage(city: City, repository: WeatherRepository, imperial: Boolean, now: Long, showMapPreview: Boolean, onWeather: (Weather)->Unit, onMetric: (Weather,Metric)->Unit,onMap: ()->Unit) {
 val context=LocalContext.current
 val compact=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp<500
 val fontScale=androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
 var weather by remember(city.id) {mutableStateOf(repository.cached(city))}
 var refreshing by remember(city.id) {mutableStateOf(false)}
 var error by remember(city.id) {mutableStateOf<String?>(null)}
 var tick by remember {mutableIntStateOf(0)}
 val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
 val latestWeather by rememberUpdatedState(weather)
 DisposableEffect(lifecycle,city.id) {
  val observer=androidx.lifecycle.LifecycleEventObserver {_,event -> if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME && latestWeather?.let {Instant.now().epochSecond-it.fetchedAt>=900}==true) tick++ }
  lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
 }
 LaunchedEffect(city.id,city.latitude,city.longitude,tick) {
  val old=repository.cached(city)
  weather=old; old?.let(onWeather)
  if(tick==0 && old!=null && now-old.fetchedAt in 0L until 900L) return@LaunchedEffect
  refreshing=true
  try { val fresh=repository.forecast(city); weather=fresh; onWeather(fresh);error=null }
  catch(e: CancellationException) {throw e}
  catch(e: Exception) { error=if(weather!=null) "Couldn’t refresh. Using saved forecast." else "Couldn’t load weather. Check your connection and retry." }
  finally {refreshing=false}
 }
 LaunchedEffect(lifecycle,city.id) {
  lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
   while(true) { delay(15 * 60 * 1000L); tick++ }
  }
 }
 PullToRefreshBox(isRefreshing=refreshing,onRefresh={tick++},modifier=Modifier.fillMaxSize()) {
  if(weather==null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
   WeatherGlyph(2,true,88)
   Text(city.name,fontSize=32.sp)
   Spacer(Modifier.height(20.dp))
   if(refreshing) { CircularProgressIndicator(color=Ink);Text("Loading your forecast…",Modifier.padding(16.dp)) }
   else {Text(error ?: "Weather is unavailable",textAlign=TextAlign.Center);Button(onClick={tick++}){Text("Retry")}}
  } else {
   val snapshot=weather!!
   val source=snapshot.conditionSourceAt(now)
   val w=snapshot.atTime(now)
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    Column(Modifier.fillMaxWidth().padding(top=if(compact)8.dp else 30.dp,bottom=if(compact)12.dp else 22.dp),horizontalAlignment=Alignment.CenterHorizontally) {
     Text(w.city.name,fontSize=32.sp,fontWeight=FontWeight.Normal,textAlign=TextAlign.Center)
     if(w.city.country.isNotEmpty()) Text(w.city.country,fontSize=14.sp,color=Muted)
     Text(temp(w.current.temperature,imperial),fontSize=if(compact)64.sp else 100.sp,fontWeight=FontWeight.ExtraLight,lineHeight=if(compact)76.sp else 112.sp,modifier=Modifier.semantics {contentDescription="${temp(w.current.temperature,imperial)} ${if(imperial) "Fahrenheit" else "Celsius"}"})
     Text(condition(w.current.code),fontSize=22.sp,color=Muted)
     Text("H:${temp(w.days.firstOrNull()?.high,imperial)}   L:${temp(w.days.firstOrNull()?.low,imperial)}",fontSize=20.sp)
    }
    if(error!=null) Notice(error!!) {tick++}
    when(source) {
     ConditionSource.FORECAST -> Glass {
      Text("Estimated now · ${localTime(now,w.timezone)}",fontSize=16.sp,fontWeight=FontWeight.Medium)
      Text("From saved hourly forecast · Downloaded ${localTime(w.fetchedAt,w.timezone,"MMM d, " + DisplayClock.pattern)}",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=4.dp))
     }
     ConditionSource.UNAVAILABLE -> Notice("Saved forecast doesn’t cover this time. Connect to update weather.") {tick++}
     ConditionSource.CURRENT -> Unit
    }
    Glass {
     val today=w.days.firstOrNull()
     Text(buildString {
      append(condition(w.current.code)); append(" today.")
      today?.precipitationProbability?.let {append(" Rain chance ${it.display()}%.")}
      w.current.wind?.let {append(" Wind ${speed(it,imperial)}.")}
     },fontSize=16.sp,lineHeight=23.sp)
     HorizontalDivider(Modifier.padding(vertical=12.dp),color=Ink.copy(alpha=.15f))
     Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
      val currentHour=w.forecastHourAt(now)?.copy(time=now,temperature=w.current.temperature,code=w.current.code)
      (listOfNotNull(currentHour)+w.hours.filter {it.time>now}).take(48).forEach {h ->
       Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.widthIn(min=42.dp).clickable {onMetric(snapshot,Metric.TEMPERATURE)}.padding(vertical=5.dp)) {
        Text(if(h.time==now) "Now" else localTime(h.time,w.timezone,if(android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH" else "ha"),fontSize=15.sp)
        WeatherGlyph(h.code, isDayAt(h.time,w),30)
        if((h.precipitationProbability ?: 0.0)>0) Text("${h.precipitationProbability.display()}%",fontSize=12.sp,color=Accent) else Spacer(Modifier.height(15.dp))
        Text(temp(h.temperature,imperial),fontSize=20.sp)
       }
      }
     }
    }
    Glass {
     PanelTitle(Icons.Outlined.CalendarMonth,"10-DAY FORECAST")
     val min=w.days.mapNotNull{it.low}.minOrNull() ?: 0.0
     val max=w.days.mapNotNull{it.high}.maxOrNull() ?: 1.0
     w.days.forEachIndexed {i,d ->
      if(i>0) HorizontalDivider(color=Ink.copy(alpha=.10f))
      Row(Modifier.fillMaxWidth().clickable {onMetric(snapshot,Metric.TEMPERATURE.copyForDate(d.date))}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
       Text(if(d.date==Instant.ofEpochSecond(now).atZone(ZoneId.of(w.timezone)).toLocalDate().toString()) "Today" else runCatching { LocalDate.parse(d.date).dayOfWeek.name.take(3).lowercase().replaceFirstChar {it.uppercase()} }.getOrDefault(d.date),modifier=Modifier.width((54*fontScale).dp),fontSize=18.sp)
       Column(horizontalAlignment=Alignment.CenterHorizontally) {WeatherGlyph(d.code,true,26);if((d.precipitationProbability ?: 0.0)>0) Text("${d.precipitationProbability.display()}%",fontSize=11.sp,color=Accent)}
       Text(temp(d.low,imperial),color=Muted,fontSize=17.sp,modifier=Modifier.widthIn(min=(32*fontScale).dp),textAlign=TextAlign.End)
       Canvas(Modifier.weight(1f).height(8.dp).semantics {contentDescription="Low ${temp(d.low,imperial)}, high ${temp(d.high,imperial)}"}) {
        drawLine(Ink.copy(alpha=.12f),Offset(0f,size.height/2),Offset(size.width,size.height/2),strokeWidth=size.height,cap=StrokeCap.Round)
        if(d.low!=null && d.high!=null) { val range=(max-min).coerceAtLeast(1.0);val left=((d.low-min)/range).toFloat();val right=((d.high-min)/range).toFloat()
         drawLine(Brush.horizontalGradient(listOf(Color(0xFF7BD9CF),Color(0xFFFFD17B))),Offset(size.width*left,size.height/2),Offset(size.width*right.coerceAtLeast(left+.02f),size.height/2),strokeWidth=size.height,cap=StrokeCap.Round)
        }
       }
       Text(temp(d.high,imperial),fontSize=17.sp,modifier=Modifier.widthIn(min=(32*fontScale).dp),textAlign=TextAlign.End)
      }
     }
    }
    if(showMapPreview) WeatherMapPreview(w.city,onMap)
    val metrics=Metric.entries
    BoxWithConstraints {
     val columns=if(maxWidth>600.dp) 3 else if(maxWidth>330.dp && androidx.compose.ui.platform.LocalConfiguration.current.fontScale<1.5f) 2 else 1
     Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
      metrics.chunked(columns).forEach {row -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {row.forEach {m ->
       Glass(Modifier.weight(1f).heightIn(min=168.dp).clickable {onMetric(snapshot,m)}) {
        PanelTitle(m.icon,m.title.uppercase())
        Text(metricValue(m,w,imperial),fontSize=30.sp,fontWeight=FontWeight.Normal,modifier=Modifier.padding(top=6.dp))
        Text(metricSummary(m,w,imperial),fontSize=14.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
       }
      };if(row.size<columns) Spacer(Modifier.weight((columns-row.size).toFloat())) } }
     }
    }
    Text("Downloaded ${localTime(w.fetchedAt,w.timezone,"MMM d, " + DisplayClock.pattern)} · ${w.timezone.replace('_',' ')}\nForecast & air quality: Open-Meteo / CAMS\nCurrent conditions and air quality are model estimates.",fontSize=12.sp,lineHeight=18.sp,color=Muted,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=10.dp))
    TextButton(onClick={android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://open-meteo.com/")).also {contextIntent -> contextIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(contextIntent)}}) {Text("Weather data sources",color=Muted)}
   }
  }
 }
}

/** Advance cached conditions even without a network request, and immediately on foregrounding. */
@Composable
private fun rememberWeatherTime(): Long {
 var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
 val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
 LaunchedEffect(lifecycle) {
  lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
   while(true) { now=Instant.now().epochSecond; delay(30_000L) }
  }
 }
 return now
}

private fun cityKey(weather: Weather)=weather.city.id

@Composable
private fun Glass(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
 val shape=RoundedCornerShape(24.dp)
 // Transparent layers keep the atmospheric sky visible; the dark tint protects text contrast.
 Column(modifier.fillMaxWidth().clip(shape)
  .background(Brush.verticalGradient(listOf(Color(0x66132945),Color(0x8810223C))))
  .background(Brush.linearGradient(listOf(Color.White.copy(alpha=.09f),Color.Transparent,Color.White.copy(alpha=.025f))))
  .border(1.dp,Brush.linearGradient(listOf(Color.White.copy(alpha=.25f),Color.White.copy(alpha=.045f),Color.White.copy(alpha=.11f))),shape)
  .padding(16.dp),content=content)
}
@Composable
private fun PanelTitle(icon: ImageVector,title: String) {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {Icon(icon,null,Modifier.size(16.dp),tint=Muted);Text(title,fontSize=12.sp,fontWeight=FontWeight.Medium,color=Muted,letterSpacing=.5.sp)}}
@Composable
private fun Notice(message: String,onRetry: ()->Unit) {Glass {Text(message,fontSize=14.sp);TextButton(onClick=onRetry){Text("Retry",color=Accent)}}}

private enum class Metric(val title: String,val icon: ImageVector) {
 TEMPERATURE("Temperature",Icons.Outlined.Thermostat), FEELS("Feels like",Icons.Outlined.Person), RAIN("Precipitation",Icons.Outlined.WaterDrop), WIND("Wind",Icons.Outlined.Air), HUMIDITY("Humidity",Icons.Outlined.Water), DEW("Dew point",Icons.Outlined.Opacity), UV("UV index",Icons.Outlined.WbSunny), VISIBILITY("Visibility",Icons.Outlined.Visibility), PRESSURE("Pressure",Icons.Outlined.Speed), SUN("Sunrise & sunset",Icons.Outlined.WbTwilight), AIR("Air quality",Icons.Outlined.BlurOn);
 fun copyForDate(date: String): Metric {selectedDate=date;return this}
 companion object { var selectedDate: String?=null }
}
private fun nearest(w: Weather): Hour? = w.forecastHourAt(w.current.time)
private fun metricValue(m: Metric,w: Weather,imperial: Boolean): String {val h=nearest(w);return when(m) {
 Metric.TEMPERATURE -> temp(w.current.temperature,imperial)
 Metric.FEELS -> temp(w.current.feelsLike,imperial)
 Metric.RAIN -> w.days.firstOrNull()?.precipitation?.let {if(imperial) "${(it/25.4).display(2)} in" else "${it.display(1)} mm"} ?: "—"
 Metric.WIND -> speed(w.current.wind,imperial)
 Metric.HUMIDITY -> w.current.humidity?.let {"${it.display()}%"} ?: "—"
 Metric.DEW -> temp(h?.dewPoint,imperial)
 Metric.UV -> w.days.firstOrNull()?.uv.display(1)
 Metric.VISIBILITY -> h?.visibility?.let {"${(it/if(imperial) 1609.344 else 1000.0).display(1)} ${if(imperial) "mi" else "km"}"} ?: "—"
 Metric.PRESSURE -> w.current.pressure?.let {if(imperial) "${(it*.029529983).display(2)} inHg" else "${it.display()} hPa"} ?: "—"
 Metric.SUN -> w.days.firstOrNull()?.sunset?.let{localTime(it,w.timezone)} ?: "—"
 Metric.AIR -> w.air?.europeanAqi.display()
}}
private fun metricSummary(m: Metric,w: Weather,imperial: Boolean): String {val h=nearest(w);return when(m) {
 Metric.TEMPERATURE -> "Today’s high ${temp(w.days.firstOrNull()?.high,imperial)}. Tap to explore the forecast."
 Metric.FEELS -> "Apparent temperature combines temperature, humidity and wind."
 Metric.RAIN -> "Today’s total. Chance ${w.days.firstOrNull()?.precipitationProbability.display()}%."
 Metric.WIND -> "Gusts ${speed(w.current.gusts,imperial)} · ${compass(w.current.windDirection)}"
 Metric.HUMIDITY -> "Relative humidity near ground level."
 Metric.DEW -> "Temperature at which air becomes saturated."
 Metric.UV -> "Today’s peak · ${uvLabel(w.days.firstOrNull()?.uv)}"
 Metric.VISIBILITY -> "Model estimate of horizontal visibility."
 Metric.PRESSURE -> "Atmospheric pressure at sea level."
 Metric.SUN -> "Sunset · Sunrise ${w.days.firstOrNull()?.sunrise?.let {localTime(it,w.timezone)} ?: "unavailable"}"
 Metric.AIR -> if(w.air==null) "Air-quality data unavailable for this location." else "European AQI · ${aqiLabel(w.air.europeanAqi)}\nModel estimate, not a local sensor."
}}
private fun uvLabel(v: Double?)=when {v==null->"Unavailable";v<3->"Low";v<6->"Moderate";v<8->"High";v<11->"Very high";else->"Extreme"}
private fun aqiLabel(v: Double?)=when {v==null->"Unavailable";v<=20->"Good";v<=40->"Fair";v<=60->"Moderate";v<=80->"Poor";v<=100->"Very poor";else->"Extremely poor"}
private fun compass(v: Double?): String {if(v==null)return "Direction unavailable";return arrayOf("N","NE","E","SE","S","SW","W","NW")[((v/45).roundToInt()%8+8)%8]}
private fun isDayAt(time: Long,w: Weather): Boolean {val d=w.days.firstOrNull {it.date==Instant.ofEpochSecond(time).atZone(ZoneId.of(w.timezone)).toLocalDate().toString()};return if(d?.sunrise!=null&&d.sunset!=null) time in d.sunrise..d.sunset else w.current.isDay}

@Composable
private fun MetricScreen(metric: Metric,w: Weather,imperial: Boolean,onClose: ()->Unit) {
 val context=LocalContext.current
 var date by remember(metric) {mutableStateOf(Metric.selectedDate ?: w.days.firstOrNull()?.date)}
 val displayedDate=date?.takeIf {selected -> w.days.any {it.date==selected}} ?: w.days.firstOrNull()?.date
 DisposableEffect(Unit) {onDispose {Metric.selectedDate=null}}
 val hours=w.hours.filter {Instant.ofEpochSecond(it.time).atZone(ZoneId.of(w.timezone)).toLocalDate().toString()==displayedDate}
 fun value(h: Hour): Double? = when(metric) {Metric.TEMPERATURE->h.temperature;Metric.FEELS->h.feelsLike;Metric.RAIN->h.precipitation;Metric.WIND->h.wind;Metric.HUMIDITY->h.humidity;Metric.DEW->h.dewPoint;Metric.UV->h.uv;Metric.VISIBILITY->h.visibility?.div(if(imperial)1609.344 else 1000.0);Metric.PRESSURE->h.pressure;else->null}
 Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
  ScreenHeader(metric.title,onClose)
  Glass {Text(metricValue(metric,w,imperial),fontSize=44.sp);Text(metricSummary(metric,w,imperial),fontSize=16.sp,lineHeight=24.sp)}
  if(metric!=Metric.SUN && metric!=Metric.AIR) {
   Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {w.days.forEach {d ->FilterChip(selected=displayedDate==d.date,onClick={date=d.date},label={Text(d.date.takeLast(5))})}}
   Glass {
    Text("${displayedDate ?: "Today"} · ${w.city.name}",fontSize=18.sp)
    Text("${metric.title} · ${w.timezone}",fontSize=13.sp,color=Muted)
    val points=hours.map {value(it)?.let {v -> when(metric) {Metric.TEMPERATURE,Metric.FEELS,Metric.DEW -> if(imperial)v*9/5+32 else v;Metric.WIND -> if(imperial)v/1.609344 else v;Metric.RAIN -> if(imperial)v/25.4 else v;Metric.PRESSURE -> if(imperial)v*.029529983 else v;else->v}}}
    Chart(points,Modifier.fillMaxWidth().height(180.dp).padding(top=22.dp,bottom=12.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {if(hours.isNotEmpty())listOf(0,hours.size/3,hours.size*2/3,hours.lastIndex).distinct().forEach {i->Text(localTime(hours[i].time,w.timezone),fontSize=12.sp,color=Muted)}}
    if(points.all{it==null}) Text("No forecast values available for this day.",Modifier.padding(top=16.dp))
   }
   Glass {hours.forEach {h ->Row(Modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.SpaceBetween) {
    Text(localTime(h.time,w.timezone),color=Muted)
    Text(when(metric){Metric.TEMPERATURE,Metric.FEELS,Metric.DEW->temp(value(h),imperial);Metric.WIND->speed(value(h),imperial);Metric.RAIN->value(h)?.let{if(imperial) "${(it/25.4).display(2)} in" else "${it.display(1)} mm"} ?: "—";Metric.HUMIDITY->value(h)?.let{"${it.display()}%"} ?: "—";Metric.PRESSURE->value(h)?.let{if(imperial) "${(it*.029529983).display(2)} inHg" else "${it.display()} hPa"} ?: "—";Metric.VISIBILITY->"${value(h).display(1)} ${if(imperial)"mi" else "km"}";else->value(h).display(1)})
   }}}
  } else if(metric==Metric.SUN) {
   Glass {w.days.forEach {d ->Text("${d.date}\nSunrise ${d.sunrise?.let{localTime(it,w.timezone)} ?: "Unavailable"}   ·   Sunset ${d.sunset?.let{localTime(it,w.timezone)} ?: "Unavailable"}",Modifier.padding(vertical=10.dp),lineHeight=25.sp)}}
  } else Glass {
   Text("European AQI: ${w.air?.europeanAqi.display()}\nUS AQI: ${w.air?.usAqi.display()}\nPM2.5: ${w.air?.pm25.display(1)} µg/m³\nPM10: ${w.air?.pm10.display(1)} µg/m³",lineHeight=30.sp)
   Text(w.air?.let{"Model valid time ${localTime(it.time,w.timezone)}"} ?: "Air quality is currently unavailable.",color=Muted)
  }
  TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(if(metric==Metric.AIR) "https://open-meteo.com/en/docs/air-quality-api" else "https://open-meteo.com/")))}) {Text(if(metric==Metric.AIR) "CAMS air quality via Open-Meteo" else "Weather data by Open-Meteo",color=Muted)}
 }
}

@Composable
private fun Chart(values: List<Double?>,modifier: Modifier) {
 val valid=values.filterNotNull();val low=valid.minOrNull() ?: 0.0;val high=valid.maxOrNull() ?: 1.0
 Column {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(if(valid.isEmpty()) "Min —" else "Min ${low.display(1)}",fontSize=12.sp,color=Muted);Text(if(valid.isEmpty()) "Max —" else "Max ${high.display(1)}",fontSize=12.sp,color=Muted)}
  Canvas(modifier.semantics {contentDescription=if(valid.isEmpty()) "No data" else "Forecast chart. Minimum ${low.display(1)}, maximum ${high.display(1)}. Hourly values below."}) {
   repeat(4){i ->val y=size.height*i/3;drawLine(Ink.copy(alpha=.12f),Offset(0f,y),Offset(size.width,y),1f)}
   val range=(high-low).coerceAtLeast(1.0);var previous: Offset?=null
   values.forEachIndexed {i,v ->if(v==null)previous=null else {val point=Offset(size.width*i/(values.size-1).coerceAtLeast(1),size.height*(1-((v-low)/range).toFloat()));previous?.let{drawLine(Accent,it,point,4f,StrokeCap.Round)};previous=point}}
  }
 }
}

@Composable
private fun CityScreen(cities: List<City>,repository: WeatherRepository,message: String?,locating: Boolean,onLocation: ()->Unit,onAdd: (City)->Unit,onRemove: (City)->Unit,onClose: ()->Unit) {
 val context=LocalContext.current
 var query by remember {mutableStateOf("")};var results by remember {mutableStateOf<List<City>>(emptyList())};var searching by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
 LaunchedEffect(query) {results=emptyList();error=null;searching=false;if(query.trim().length<2)return@LaunchedEffect;searching=true;try {delay(350);results=repository.search(query.trim())}catch(e:CancellationException){throw e}catch(e:Exception){error="Search unavailable. Check your connection."}finally{searching=false}}
 Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
  if(cities.isNotEmpty()) ScreenHeader("Weather",onClose) else {Spacer(Modifier.height(32.dp));WeatherGlyph(2,true,72);Text("Weather",fontSize=44.sp,fontWeight=FontWeight.Light);Text("Your day, at a glance.",fontSize=20.sp,color=Muted)}
  OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Search city or postal code")},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty())IconButton(onClick={query=""}){Icon(Icons.Outlined.Close,"Clear search")}},shape=RoundedCornerShape(16.dp))
  Button(onClick=onLocation,enabled=!locating,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)) {Icon(Icons.Outlined.MyLocation,null);Spacer(Modifier.width(10.dp));Text(if(locating) "Finding your location…" else "Use my location")}
  message?.let{Text(it,color=Muted)}
  if(searching) LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let{Text(it)}
  LazyColumn(modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   if(query.trim().length>=2) {
    if(!searching&&results.isEmpty()&&error==null)item{Text("No cities found. Try a nearby city.",color=Muted)}
    items(results,key={it.id}) {city ->Glass(Modifier.clickable {onAdd(city)}) {Text(city.name,fontSize=24.sp);Text(city.country,color=Muted)}}
   } else {
    if(cities.isEmpty())item {Text("Search for a city or choose your current location. Location access is optional.",color=Muted,lineHeight=24.sp,modifier=Modifier.padding(top=12.dp))}
    items(cities,key={it.id}) {city ->Glass {Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f).clickable{onAdd(city)}.padding(vertical=8.dp)){Text(city.name,fontSize=26.sp);Text(city.country.ifEmpty{"Current location"},color=Muted)};IconButton(onClick={onRemove(city)}) {Icon(Icons.Outlined.RemoveCircleOutline,"Remove ${city.name}")}}}}
   }
  }
  TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://open-meteo.com/en/docs/geocoding-api")))}) {Text("City search: GeoNames via Open-Meteo",fontSize=12.sp,color=Muted)}
 }
}
@Composable
private fun SettingsScreen(imperial: Boolean,onImperial: (Boolean)->Unit,onClose: ()->Unit) {
 val context=LocalContext.current
 var showLicenses by remember {mutableStateOf(false)}
 Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
  ScreenHeader("Settings",onClose)
  Glass {Text("Units",fontSize=22.sp);Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text(if(imperial) "Fahrenheit · mph · inches" else "Celsius · km/h · millimeters",Modifier.weight(1f));Switch(imperial,onImperial,modifier=Modifier.semantics {contentDescription="Use imperial units"})}}
  Glass {Text("Made for a quieter forecast",fontSize=22.sp);Text("No ads, accounts, analytics, or background location tracking. Location is requested only when you choose it. Weather and map services receive the coordinates needed for their requests.",Modifier.padding(top=12.dp),lineHeight=24.sp)}
  Glass {Text("Data & attribution",fontSize=22.sp);Text("Forecasts: Open-Meteo\nAir quality: CAMS via Open-Meteo\nRainfall radar: RainViewer\nMap data: © OpenStreetMap contributors\nDark basemap: OpenFreeMap · © OpenMapTiles\nMaps: MapLibre Native",Modifier.padding(top=12.dp),lineHeight=26.sp)
   listOf("Open-Meteo" to "https://open-meteo.com/", "RainViewer" to "https://www.rainviewer.com/", "OpenStreetMap contributors" to "https://www.openstreetmap.org/copyright", "OpenFreeMap" to "https://openfreemap.org/", "OpenMapTiles style credits and license" to "https://github.com/openmaptiles/dark-matter-gl-style/blob/master/LICENSE.md").forEach{(name,url)->TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)))}){Text(name)}}
  }
  Text("Weather 1.1.1\nCreated by Kian Konrad Tajbakhsh\nOpen source · MIT license",fontSize=13.sp,color=Muted,lineHeight=21.sp)
  TextButton(onClick={showLicenses=true}) {Text("Open-source licenses")}
  TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/Kian-hdr/Weather")))}) {Text("Source code & downloads")}
 }
 if(showLicenses) {
  val notice=remember {listOf("MIT.txt","NOTICE.txt","Apache-2.0.txt","MapLibre_Android_13.6.1.txt","Bundled_Dependency_Notices.txt").joinToString("\n\n") {name -> context.assets.open("legal/$name").bufferedReader().use {it.readText()} }}
  AlertDialog(onDismissRequest={showLicenses=false},title={Text("Open-source licenses")},
   text={LazyColumn(Modifier.heightIn(max=480.dp)) {items(notice.split("\n\n")) {paragraph -> Text(paragraph,fontSize=12.sp,lineHeight=17.sp,modifier=Modifier.padding(bottom=12.dp))}}},
   confirmButton={TextButton(onClick={showLicenses=false}){Text("Done")}})
 }
}
@Composable
private fun ScreenHeader(title: String,onClose: ()->Unit) {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {IconButton(onClick=onClose) {Icon(Icons.Outlined.ArrowBack,"Back")};Text(title,fontSize=28.sp,fontWeight=FontWeight.Medium)}}

@Composable
fun WeatherGlyph(code: Int?,day: Boolean,size: Int=32) {
 Canvas(Modifier.size(size.dp).semantics {contentDescription=condition(code)}) {
  val s=this.size.minDimension
  val cloudy=code!=0 && code!=1
  val rainy=code in listOf(51,53,55,56,57,61,63,65,66,67,80,81,82,95,96,99)
  val snowy=code in listOf(71,73,75,77,85,86)
  val center=Offset(s*.55f,s*.38f)
  if(code==0 || code==1 || code==2) {
   if(day) {drawCircle(Color(0xFFFFD781),s*.21f,center);repeat(8){i->val a=i*PI/4;drawLine(Color(0xFFFFD781),center+Offset(cos(a).toFloat()*s*.27f,sin(a).toFloat()*s*.27f),center+Offset(cos(a).toFloat()*s*.33f,sin(a).toFloat()*s*.33f),s*.035f,StrokeCap.Round)}}
   else {
    val moon=Path().apply {addOval(androidx.compose.ui.geometry.Rect(center-Offset(s*.25f,s*.25f),center+Offset(s*.25f,s*.25f)))}
    val cutCenter=center+Offset(s*.13f,-s*.09f)
    val cut=Path().apply {addOval(androidx.compose.ui.geometry.Rect(cutCenter-Offset(s*.23f,s*.23f),cutCenter+Offset(s*.23f,s*.23f)))}
    drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference,moon,cut),Color(0xFFFFE9B7))
   }
  }
  if(cloudy || code==1) {val c=Color(0xFFF0F6FF);drawCircle(c,s*.17f,Offset(s*.28f,s*.53f));drawCircle(c,s*.22f,Offset(s*.48f,s*.43f));drawCircle(c,s*.17f,Offset(s*.68f,s*.54f));drawRoundRect(c,Offset(s*.25f,s*.48f),Size(s*.48f,s*.23f),androidx.compose.ui.geometry.CornerRadius(s*.1f))}
  if(rainy && code !in listOf(95,96,99))repeat(3){i->drawLine(Color(0xFF9AD8FF),Offset(s*(.33f+i*.16f),s*.77f),Offset(s*(.28f+i*.16f),s*.9f),s*.04f,StrokeCap.Round)}
  if(code in listOf(95,96,99))drawPath(Path().apply {moveTo(s*.52f,s*.64f);lineTo(s*.35f,s*.84f);lineTo(s*.49f,s*.84f);lineTo(s*.42f,s*.99f);lineTo(s*.68f,s*.74f);lineTo(s*.54f,s*.74f);close()},Color(0xFFFFD781))
  if(snowy)repeat(3){i->drawCircle(Ink,s*.035f,Offset(s*(.3f+i*.18f),s*.83f))}
  if(code==45||code==48)repeat(2){i->drawLine(Muted,Offset(s*.2f,s*(.78f+i*.12f)),Offset(s*.8f,s*(.78f+i*.12f)),s*.04f,StrokeCap.Round)}
  if(code==null)drawCircle(Ink.copy(alpha=.5f),s*.2f,Offset(s/2,s/2),style=Stroke(s*.04f))
 }
}
@Composable
internal fun Sky(code: Int?,day: Boolean) {
 val context=LocalContext.current
 val reduce=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
 val transition=rememberInfiniteTransition(label="Sky drift")
 val drift by transition.animateFloat(0f,1f,infiniteRepeatable(tween(40000,easing=LinearEasing),RepeatMode.Reverse),label="Cloud drift")
 val fall by transition.animateFloat(0f,1f,infiniteRepeatable(tween(6000,easing=LinearEasing),RepeatMode.Restart),label="Precipitation drift")
 val colors=if(day) listOf(Color(0xFF326C9F),Color(0xFF204E7B),Color(0xFF102D50)) else listOf(Color(0xFF101B3F),Color(0xFF0B1734),Color(0xFF060D24))
 Canvas(Modifier.fillMaxSize()) {
  drawRect(Brush.verticalGradient(colors))
  val shift=if(reduce)0f else drift*size.width*.12f
  if(day && code in listOf(0,1,2))drawCircle(Brush.radialGradient(listOf(Color(0x44FFE8B0),Color.Transparent),Offset(size.width*.8f,size.height*.13f),size.width*.5f),size.width*.5f,Offset(size.width*.8f,size.height*.13f))
  if(!day)repeat(32){i->val x=((i*137)%100)/100f*size.width;val y=((i*71)%100)/100f*size.height*.5f;drawCircle(Ink.copy(alpha=.22f),if(i%3==0)1.8f else 1f,Offset(x,y))}
  val rainy=code in listOf(51,53,55,56,57,61,63,65,66,67,80,81,82,95,96,99)
  val snowy=code in listOf(71,73,75,77,85,86)
  if(rainy||snowy)repeat(if(rainy)42 else 28){i ->
   val phase=if(reduce) .3f else fall
   val x=(((i*137)%100)/100f*size.width+phase*size.width*.08f)%size.width
   val y=((((i*71)%100)/100f+phase)%1f)*size.height
   if(rainy)drawLine(Color(0x2298CFFF),Offset(x,y),Offset(x-5f,y+24f),2f,StrokeCap.Round)
   else drawCircle(Ink.copy(alpha=.20f),if(i%3==0)3f else 2f,Offset(x,y))
  }
  if(code!=0)repeat(3){i->drawOval(Brush.radialGradient(listOf(Ink.copy(alpha=.06f),Color.Transparent)),Offset(-size.width*.4f+shift+i*size.width*.3f,size.height*(.06f+i*.11f)),Size(size.width*1.1f,size.height*.23f))}
 }
}

@Suppress("MissingPermission")
private suspend fun findLocation(context: Context): Pair<Double,Double>? = suspendCancellableCoroutine {continuation ->
 val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
 val provider=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).firstOrNull{runCatching{manager.isProviderEnabled(it)}.getOrDefault(false)}
 if(provider==null) {continuation.resume(null);return@suspendCancellableCoroutine}
 val last=runCatching {manager.getLastKnownLocation(provider)}.getOrNull()
 if(last!=null && System.currentTimeMillis()-last.time<10*60*1000) {continuation.resume(last.latitude to last.longitude);return@suspendCancellableCoroutine}
 if(android.os.Build.VERSION.SDK_INT>=30) {
  val signal=CancellationSignal();continuation.invokeOnCancellation{signal.cancel()}
  manager.getCurrentLocation(provider,signal,context.mainExecutor) {location->if(continuation.isActive)continuation.resume(location?.let{it.latitude to it.longitude})}
 } else {
  val listener=object: android.location.LocationListener {
   override fun onLocationChanged(location:android.location.Location){manager.removeUpdates(this);if(continuation.isActive)continuation.resume(location.latitude to location.longitude)}
   override fun onProviderDisabled(provider:String){};override fun onProviderEnabled(provider:String){};@Deprecated("Legacy") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
  }
  manager.requestSingleUpdate(provider,listener,android.os.Looper.getMainLooper());continuation.invokeOnCancellation {manager.removeUpdates(listener)}
 }
}
