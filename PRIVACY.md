# Privacy

Weather stores saved cities, unit preferences and cached forecasts in Android app-private storage. No account, ads, analytics, background location, notification permission, or location history service is used. Android backup is disabled for this edition.

Location is optional. The Android permission request appears only after choosing **Use my location**. You can deny it and use city search instead. Coordinates for My Location remain stored as that saved weather location until it is removed or replaced; this is not continuous tracking.

Forecast and air-quality requests send selected coordinates to Open-Meteo. City search sends the entered query to Open-Meteo geocoding. Radar requests go to RainViewer; base-map, map-font and sprite requests go to OpenFreeMap, using OpenStreetMap/OpenMapTiles data. Map tile requests reveal the viewed region to these providers. Providers also receive ordinary network metadata such as IP address. Their privacy policies apply.

Caches are bounded and kept on the device. Removing the app through Android removes its local storage. Signing secrets and build credentials are never bundled in the APK.
