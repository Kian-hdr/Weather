# Third-party notices and data sources

The app's original code and artwork are licensed under [MIT](LICENSE). This does not change third-party software, data, trademarks or service terms.

- [Full notice](NOTICE)
- [Resolved runtime dependency inventory](DEPENDENCIES.md)
- [Apache License 2.0](app/src/main/assets/legal/Apache-2.0.txt)
- [MapLibre Native Android 13.6.1 license bundle, including native dependencies](app/src/main/assets/legal/MapLibre_Android_13.6.1.txt)
- [Notices retained from packaged dependencies](app/src/main/assets/legal/Bundled_Dependency_Notices.txt)

These notices are included in the APK and readable under Settings → Open-source licenses.

## Live services

| Source | Attribution and terms |
| --- | --- |
| Open-Meteo forecasts and geocoding | [Open-Meteo](https://open-meteo.com/), [data license](https://open-meteo.com/en/licence), [service terms](https://open-meteo.com/en/terms). Weather data is CC BY 4.0. The included free endpoints are for noncommercial use, below 600 calls/minute, 5,000/hour and 10,000/day. |
| GeoNames city data | [GeoNames](https://www.geonames.org/), via [Open-Meteo geocoding](https://open-meteo.com/en/docs/geocoding-api). |
| CAMS air quality | [CAMS via Open-Meteo](https://open-meteo.com/en/docs/air-quality-api). Modeled values, not local sensor readings. |
| RainViewer radar | [RainViewer](https://www.rainviewer.com/) and [API terms](https://www.rainviewer.com/api.html). Personal, educational and small-scale community use; availability is not guaranteed. |
| OpenFreeMap tiles | [OpenFreeMap terms](https://openfreemap.org/tos/). Online maps query the public service; the app has no bulk offline map download feature. |
| OpenStreetMap data | [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright), ODbL. |
| Dark map style | [© OpenMapTiles](https://openmaptiles.org/) and [style license](https://github.com/openmaptiles/dark-matter-gl-style/blob/master/LICENSE.md). BSD-3-Clause code and CC BY 4.0 design; derived from CartoDB Basemaps, Stamen and Paul Norman. The app adjusts labels, halos and weather-overlay order. |

Terms were checked on October 25, 2026 and may change. Forks that monetize, advertise or serve higher traffic must arrange appropriate provider access; the MIT source license does not grant free commercial API access. This release has no ads, subscriptions or paid features.
