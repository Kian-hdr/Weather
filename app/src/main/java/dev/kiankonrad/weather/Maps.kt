package dev.kiankonrad.weather

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private val MapPanel = Color(0xF01B304D)
private val MapInk = Color(0xFFE1ECFA)
private var mapHttpInstalled = false

/** The map HTTP client has a real application identity and a bounded, header-aware disk cache. */
private fun prepareMap(context: android.content.Context) {
    MapLibre.getInstance(context.applicationContext)
    if (!mapHttpInstalled) {
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "map-http"), 48L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Weather/1.1.1 (https://github.com/Kian-hdr/Weather; dev.kiankonrad.weather)").build()) }
            .build())
        mapHttpInstalled = true
    }
}

private const val DARK_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

@Composable
private fun NativeWeatherMap(city: City, modifier: Modifier, interactive: Boolean = true,
                             onReady: (MapLibreMap) -> Unit, onError: (String) -> Unit = {}, reload: Int = 0) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestReady by rememberUpdatedState(onReady)
    val latestError by rememberUpdatedState(onError)
    val view = remember(city.id, city.latitude, city.longitude, reload) {
        prepareMap(context)
        MapView(context).apply {
            onCreate(Bundle())
            setMaximumFps(30)
            contentDescription = "Weather map centered on ${city.name}"
            addOnDidFailLoadingMapListener { latestError("Map could not load. Check your connection.") }
            addOnTileActionListener { operation, _, _, _, _, _, _ ->
                if(operation == org.maplibre.android.tile.TileOperation.Error) post {
                    if(!isDestroyed) latestError("Some map tiles are unavailable. Check your connection or try again.")
                }
            }
            getMapAsync { map ->
                map.setPrefetchesTiles(false)
                map.setMinZoomPreference(2.0)
                map.setMaxZoomPreference(12.0)
                map.uiSettings.apply {
                    isLogoEnabled = false
                    isAttributionEnabled = false // Attribution is always visible as accessible links below the map.
                    isCompassEnabled = interactive
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                    isScrollGesturesEnabled = interactive
                    isZoomGesturesEnabled = interactive
                    isDoubleTapGesturesEnabled = interactive
                }
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(city.latitude, city.longitude), if(interactive) 5.0 else 6.0))
                map.setStyle(Style.Builder().fromUri(DARK_MAP_STYLE_URL)) { style ->
                    // Keep vector labels legible above both weather overlays; missing POI icons
                    // must never suppress their text labels.
                    style.layers.filterIsInstance<SymbolLayer>().forEach { label ->
                        label.setProperties(textColor("#CBD5E1"), textHaloColor("#09131F"), textHaloWidth(1.2f), iconOptional(true))
                    }
                    listOf("place_town", "place_city", "place_city_large").forEach { id ->
                        style.getLayerAs<SymbolLayer>(id)?.setProperties(textSize(12f))
                    }
                    val marker = JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(city.longitude).put(city.latitude)))
                    style.addSource(GeoJsonSource("city", marker.toString()))
                    style.addLayer(CircleLayer("city-dot", "city").withProperties(circleRadius(6f), circleColor("#1679f2"), circleStrokeColor("#ffffff"), circleStrokeWidth(2f)))
                    latestReady(map)
                }
            }
        }
    }
    DisposableEffect(view, lifecycle) {
        var started = false
        var resumed = false
        fun start() { if (!started) { view.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { view.onResume(); resumed = true } }
        fun pause() { if (resumed) { view.onPause(); resumed = false } }
        fun stop() { pause(); if (started) { view.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop(); view.onDestroy() }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@Composable
fun WeatherMapPreview(city: City, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha=.11f))) {
        Text("WEATHER MAP", Modifier.padding(16.dp), color=MapInk, style=MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth().height(174.dp)) {
            NativeWeatherMap(city, Modifier.fillMaxSize(), interactive=false, onReady={})
            FilledTonalButton(onClick=onOpen, modifier=Modifier.align(Alignment.BottomCenter).padding(8.dp), colors=ButtonDefaults.filledTonalButtonColors(containerColor=MapPanel, contentColor=Color.White)) { Text("Open rainfall & temperature map") }
        }
        MapAttribution(radar=false, temperature=false, compact=true)
    }
}

@Composable
fun WeatherMap(city: City, repository: MapRepository, imperial: Boolean, onClose: () -> Unit) {
    BackHandler(onBack=onClose)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var layer by rememberSaveable { mutableStateOf("Rainfall") }
    var timeline by remember { mutableStateOf<RadarTimeline?>(null) }
    var index by rememberSaveable { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf<TemperatureGrid?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var radarLoading by remember { mutableStateOf(false) }
    var temperatureLoading by remember { mutableStateOf(false) }
    val loading = if(layer=="Rainfall") radarLoading else temperatureLoading
    var radarError by remember { mutableStateOf<String?>(null) }
    var temperatureError by remember { mutableStateOf<String?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }
    val error = mapError ?: if(layer=="Rainfall") radarError else temperatureError
    var radarLoad by remember { mutableIntStateOf(0) }
    var mapGeneration by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val labelDensity=androidx.compose.ui.platform.LocalDensity.current.let {it.density * it.fontScale}

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> active=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(radarLoad) {
        radarLoading=true; radarError=null
        try {
            val followLatest = index < 0 || index == timeline?.frames?.lastIndex
            timeline=repository.radar()
            index=if(followLatest) (timeline?.frames?.lastIndex ?: 0).coerceAtLeast(0)
                else index.coerceIn(0, (timeline?.frames?.lastIndex ?: 0).coerceAtLeast(0))
            if(timeline?.frames.isNullOrEmpty()) radarError="No recent radar frames are available."
        } catch(e: CancellationException) { throw e } catch(_: Exception) { radarError="Radar unavailable. Check your connection and try again." }
        finally { radarLoading=false }
    }
    LaunchedEffect(active, layer) {
        while(active && layer=="Rainfall") { delay(300_000); radarLoad++ }
    }
    LaunchedEffect(playing, active, layer, timeline) {
        while(playing && active && layer=="Rainfall" && !timeline?.frames.isNullOrEmpty()) {
            delay(if(index==timeline!!.frames.lastIndex) 1300 else 700)
            index=(index+1) % timeline!!.frames.size
        }
    }
    LaunchedEffect(map, layer, timeline, index, grid, imperial, labelDensity) {
        map?.style?.let { style ->
            clearWeatherLayers(style)
            map?.removeAnnotations()
            val overlayAnchor = style.layers.firstOrNull { it is SymbolLayer }?.id ?: "city-dot"
            if(layer=="Rainfall") {
                val radar=timeline
                val frame=radar?.frames?.getOrNull(index)
                if(radar!=null && frame!=null) {
                    val coverage=TileSet("2.2.0", "${radar.host}/v2/coverage/0/256/{z}/{x}/{y}/0/0_0.png").apply { maxZoom=7f }
                    style.addSource(RasterSource("radar-coverage", coverage,256))
                    style.addLayerBelow(RasterLayer("radar-coverage-layer", "radar-coverage").withProperties(rasterOpacity(.32f)), overlayAnchor)
                    val tiles=TileSet("2.2.0", "${radar.host}${frame.path}/256/{z}/{x}/{y}/2/1_1.png").apply { maxZoom=7f }
                    style.addSource(RasterSource("radar", tiles,256))
                    style.addLayerBelow(RasterLayer("radar-layer", "radar").withProperties(rasterOpacity(.8f),rasterFadeDuration(0f)), overlayAnchor)
                }
            } else grid?.let { data ->
                style.addSource(GeoJsonSource("temperature", temperatureFeatures(data)))
                style.addLayerBelow(FillLayer("temperature-fill", "temperature").withProperties(fillColor(get("color")),fillOpacity(.42f),fillAntialias(false)), overlayAnchor)
                // Bitmap annotations keep numeric labels local; no third-party glyph endpoint is required.
                data.points.forEachIndexed { n,p ->
                    if(p.temperature != null && n/7%2==0 && n%7%2==0) {
                        val bitmap=temperatureLabel(temp(p.temperature,imperial),labelDensity)
                        map?.addMarker(MarkerOptions().position(LatLng(p.latitude,p.longitude)).icon(IconFactory.getInstance(context).fromBitmap(bitmap)))
                    }
                }
            }
        }
    }
    fun updateTemperatures() {
        val bounds=map?.projection?.visibleRegion?.latLngBounds ?: return
        temperatureLoading=true; temperatureError=null
        scope.launch {
            try {
                grid=repository.temperatures(bounds.longitudeWest,bounds.latitudeSouth,bounds.longitudeEast,bounds.latitudeNorth)
                dirty=false
                if(grid?.points?.none { it.temperature!=null } != false) temperatureError="Temperature data unavailable in this area."
            } catch(e:CancellationException) { throw e } catch(_:Exception) { temperatureError="Temperature map unavailable. Try updating this area again." }
            finally { temperatureLoading=false }
        }
    }
    LaunchedEffect(layer,map) { if(layer=="Temperature" && map!=null && grid==null) updateTemperatures() }

    val density = LocalDensity.current
    var topHeight by remember { mutableStateOf(150.dp) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF14243B))) {
        val wide = maxWidth > 600.dp && maxWidth > maxHeight
        val showMapSubtitle=maxHeight>420.dp
        val overlayWidth = if(wide) minOf(340.dp, (maxWidth-24.dp)/2) else maxWidth
        val safeVertical = WindowInsets.safeDrawing.asPaddingValues().let { it.calculateTopPadding() + it.calculateBottomPadding() }
        val bottomHeight = if(wide) (maxHeight-safeVertical-24.dp).coerceAtLeast(96.dp)
            else (maxHeight-topHeight-WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()-40.dp).coerceAtLeast(64.dp)
        NativeWeatherMap(city, Modifier.fillMaxSize(), onReady={ ready ->
            map=ready
            ready.addOnCameraIdleListener { dirty=true }
        }, onError={ mapError=it }, reload=mapGeneration)
        Column(Modifier.align(if(wide) Alignment.TopStart else Alignment.TopCenter)
            .width(overlayWidth).onSizeChanged { topHeight = with(density) { it.height.toDp() } }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(color=MapPanel, shape=RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back",tint=Color.White) }
                    Column(Modifier.weight(1f)) { Text(city.name,color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis); if(showMapSubtitle) Text("Weather maps",color=MapInk,fontSize=13.sp) }
                    IconButton(onClick={ map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(city.latitude,city.longitude),5.0)) }) { Icon(Icons.Default.MyLocation,"Center on ${city.name}",tint=Color.White) }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("Rainfall","Temperature").forEach { name ->
                    Button(onClick={ layer=name; playing=false; mapError=null }, modifier=Modifier.weight(1f).heightIn(min=48.dp),colors=ButtonDefaults.buttonColors(containerColor=if(layer==name)Color.White else MapPanel,contentColor=if(layer==name)Color(0xFF183D68) else Color.White)) { Text(name,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
                }
            }
            if(error!=null) Surface(color=MapPanel,shape=RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max=140.dp).verticalScroll(rememberScrollState()).padding(12.dp)) { Text(error,color=Color.White,fontSize=14.sp)
                    TextButton(onClick={ mapError=null; radarError=null; temperatureError=null; grid=null; map=null; mapGeneration++; if(layer=="Rainfall") radarLoad++ }) { Text("Try again",color=Color(0xFFB4DBFF)) }
                }
            }
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Color(0xFFB4DBFF))
        }
        Column(Modifier.align(if(wide) Alignment.BottomEnd else Alignment.BottomCenter)
            .width(overlayWidth).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(12.dp).heightIn(max=bottomHeight),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(color=MapPanel,shape=RoundedCornerShape(22.dp),modifier=Modifier.weight(1f,fill=false)) {
                Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)) {
                    // Only explanatory controls scroll. Provider credits remain visible at the foot of the card.
                    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(layer=="Temperature" && dirty) Button(onClick={ updateTemperatures() },enabled=!loading,modifier=Modifier.align(Alignment.CenterHorizontally)) { Text("Update this area") }
                        if(layer=="Rainfall") {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                IconButton(onClick={ playing=!playing },enabled=!timeline?.frames.isNullOrEmpty()) { Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,if(playing)"Pause radar" else "Play recent radar",tint=Color.White) }
                                Column { Text("Recent precipitation radar",color=Color.White,fontWeight=FontWeight.SemiBold)
                                    Text(timeline?.frames?.getOrNull(index)?.let { "Frame ${localTime(it.time,city.timezone)} · ${city.timezone}" } ?: "Waiting for radar",color=MapInk,fontSize=12.sp)
                                }
                            }
                            if((timeline?.frames?.size ?: 0)>1) Slider(value=index.coerceAtLeast(0).toFloat(),onValueChange={ playing=false;index=it.roundToInt() },valueRange=0f..timeline!!.frames.lastIndex.toFloat(),steps=(timeline!!.frames.size-2).coerceAtLeast(0),modifier=Modifier.semantics { contentDescription="Radar timeline" })
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF5BBBE8),Color(0xFF63CA73),Color(0xFFF5D44B),Color(0xFFEB536D),Color(0xFFAE6CD4)))))
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Light",color=MapInk,fontSize=12.sp);Text("Heavy",color=MapInk,fontSize=12.sp) }
                            Text("Shaded areas lack radar coverage. Frame times combine radar observations; blank areas do not prove dry weather. Detail is limited above zoom 7.",color=MapInk,fontSize=11.sp,lineHeight=15.sp)
                        } else {
                            Text("Interpolated model temperature",color=Color.White,fontWeight=FontWeight.SemiBold)
                            Text(grid?.points?.firstOrNull { it.temperature!=null }?.let { "Valid ${localTime(it.time,city.timezone)} · ${city.timezone}" } ?: "Load temperatures for this area",color=MapInk,fontSize=12.sp)
                            val readings=grid?.points?.mapNotNull { it.temperature }.orEmpty()
                            if(readings.isNotEmpty()) Text("Area range: ${temp(readings.min(),imperial)} to ${temp(readings.max(),imperial)} ${if(imperial) "F" else "C"} · ${readings.size} of 49 samples",color=Color.White,fontSize=13.sp,
                                modifier=Modifier.semantics { contentDescription="Temperature map sample range, ${temp(readings.min(),imperial)} to ${temp(readings.max(),imperial)} degrees ${if(imperial) "Fahrenheit" else "Celsius"}. ${readings.size} of 49 regional samples available." })
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(0f to Color(0xFF765BD5), (1f/3) to Color(0xFF3A9BDC), .5f to Color(0xFF53C4AC), (2f/3) to Color(0xFFF1CE55), (5f/6) to Color(0xFFF07A50), 1f to Color(0xFFCC427D))))
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { listOf(-20.0,0.0,20.0,40.0).forEach { Text(temp(it,imperial),color=MapInk,fontSize=12.sp) } }
                            Text("Regional 7 × 7 samples, interpolated between valid points. Transparent gaps have no data. Not street-level measurements.",color=MapInk,fontSize=11.sp,lineHeight=15.sp)
                        }
                    }
                    MapAttribution(layer=="Rainfall",layer=="Temperature")
                }
            }
        }
    }
}

@Composable
private fun MapAttribution(radar:Boolean,temperature:Boolean,compact:Boolean=false) {
    val uri=LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={ uri.openUri("https://www.openstreetmap.org/copyright") },contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("© OpenStreetMap",fontSize=if(compact)11.sp else 12.sp,color=MapInk) }
            if(radar || temperature) TextButton(onClick={ uri.openUri(if(radar)"https://www.rainviewer.com" else "https://open-meteo.com") },contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text(if(radar)"RainViewer" else "Open-Meteo",fontSize=12.sp,color=MapInk) }
        }
        TextButton(onClick={ uri.openUri("https://openmaptiles.org/") },contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text("Dark map: OpenFreeMap · © OpenMapTiles",fontSize=11.sp,color=MapInk)
        }
    }
}

private fun clearWeatherLayers(style:Style) {
    listOf("radar-layer","radar-coverage-layer","temperature-fill").forEach { if(style.getLayer(it)!=null)style.removeLayer(it) }
    listOf("radar","radar-coverage","temperature").forEach { if(style.getSource(it)!=null)style.removeSource(it) }
}

/** Bilinear colors within cells whose four samples exist. Never bridges missing data. */
internal fun temperatureFeatures(grid:TemperatureGrid):String {
    val features=JSONArray()
    if(grid.points.size!=49)return JSONObject().put("type","FeatureCollection").put("features",features).toString()
    for(row in 0 until 6)for(col in 0 until 6) {
        val a=grid.points[row*7+col];val b=grid.points[row*7+col+1];val c=grid.points[(row+1)*7+col];val d=grid.points[(row+1)*7+col+1]
        if(listOf(a,b,c,d).any { it.temperature==null })continue
        // Keep longitude unwrapped per cell so an antimeridian viewport never paints across the planet.
        fun near(lon:Double,ref:Double):Double { var n=lon;while(n-ref>180)n-=360;while(n-ref< -180)n+=360;return n }
        val left=a.longitude;val right=near(b.longitude,left)
        if(abs(right-left)>180)continue
        for(y in 0 until 4)for(x in 0 until 4) {
            val u=(x+.5)/4;val v=(y+.5)/4
            val t=(a.temperature!!*(1-u)+b.temperature!!*u)*(1-v)+(c.temperature!!*(1-u)+d.temperature!!*u)*v
            val west=left+(right-left)*x/4;val east=left+(right-left)*(x+1)/4
            val south=a.latitude+(c.latitude-a.latitude)*y/4;val north=a.latitude+(c.latitude-a.latitude)*(y+1)/4
            val ring=JSONArray()
            listOf(west to south,east to south,east to north,west to north,west to south).forEach { ring.put(JSONArray().put(it.first).put(it.second)) }
            features.put(JSONObject().put("type","Feature").put("properties",JSONObject().put("color",temperatureColor(t))).put("geometry",JSONObject().put("type","Polygon").put("coordinates",JSONArray().put(ring))))
        }
    }
    return JSONObject().put("type","FeatureCollection").put("features",features).toString()
}
private fun temperatureColor(c:Double):String {
    val stops=listOf(-20.0 to 0x765BD5,0.0 to 0x3A9BDC,10.0 to 0x53C4AC,20.0 to 0xF1CE55,30.0 to 0xF07A50,40.0 to 0xCC427D)
    val upper=stops.indexOfFirst { c<=it.first }.let { if(it<0)stops.lastIndex else it }
    if(upper==0)return "#765BD5"
    val (lo,lc)=stops[upper-1];val(hi,hc)=stops[upper];val f=((c-lo)/(hi-lo)).coerceIn(0.0,1.0)
    fun channel(shift:Int)=(((lc shr shift)and 255)*(1-f)+((hc shr shift)and 255)*f).roundToInt()
    return "#%02x%02x%02x".format(channel(16),channel(8),channel(0))
}
private fun temperatureLabel(label:String,density:Float):Bitmap {
    val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=14*density;isFakeBoldText=true;color=android.graphics.Color.WHITE }
    val width=(p.measureText(label)+16*density).roundToInt();val height=(30*density).roundToInt()
    return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { bitmap ->
        val c=AndroidCanvas(bitmap)
        val bg=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.argb(210,22,43,67) }
        c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),9*density,9*density,bg)
        c.drawText(label,8*density,height/2f-(p.ascent()+p.descent())/2,p)
    }
}
