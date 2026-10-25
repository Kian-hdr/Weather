package dev.kiankonrad.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MapGeometryTest {
    private fun validGrid(west: Double = 12.0, south: Double = 50.0, east: Double = 14.0, north: Double = 53.0): TemperatureGrid {
        val grid = DataCodec.gridCoordinates(west, south, east, north)
        return grid.copy(points = grid.points.mapIndexed { index, point ->
            point.copy(temperature = 8.0 + index / 7 + index % 7, time = 1792144800L)
        }, fetchedAt = 1792144900L)
    }

    private fun polygons(grid: TemperatureGrid): JSONArray = JSONObject(temperatureFeatures(grid)).getJSONArray("features")
    private fun ring(features: JSONArray, index: Int): JSONArray = features.getJSONObject(index)
        .getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)

    @Test fun completeGridInterpolatesThirtySixCellsWithSixteenSubcellsEach() {
        val features = polygons(validGrid())
        assertEquals(576, features.length())
        for (index in 0 until features.length()) {
            val feature = features.getJSONObject(index)
            assertEquals("Polygon", feature.getJSONObject("geometry").getString("type"))
            assertTrue(feature.getJSONObject("properties").getString("color").matches(Regex("#[0-9a-fA-F]{6}")))
            val points = ring(features, index)
            assertEquals(5, points.length())
            assertEquals(points.getJSONArray(0).toString(), points.getJSONArray(4).toString())
        }
    }

    @Test fun missingCenterSampleLeavesAllFourNeighboringCellsTransparent() {
        val grid = validGrid()
        val missing = grid.copy(points = grid.points.mapIndexed { index, point ->
            if (index == 3 * 7 + 3) point.copy(temperature = null) else point
        })
        val features = polygons(missing)
        assertEquals(512, features.length())
        val holeWest = grid.points[2 * 7 + 2].longitude
        val holeEast = grid.points[4 * 7 + 4].longitude
        val holeSouth = grid.points[2 * 7 + 2].latitude
        val holeNorth = grid.points[4 * 7 + 4].latitude
        for (index in 0 until features.length()) {
            val points = ring(features, index)
            val centerLon = (points.getJSONArray(0).getDouble(0) + points.getJSONArray(2).getDouble(0)) / 2
            val centerLat = (points.getJSONArray(0).getDouble(1) + points.getJSONArray(2).getDouble(1)) / 2
            assertFalse("Interpolation must not fill the area bordering a missing sample",
                centerLon > holeWest && centerLon < holeEast && centerLat > holeSouth && centerLat < holeNorth)
        }
    }

    @Test fun antimeridianCellsHaveNarrowEdgesAndRemainWithinRequestedRegion() {
        val grid = validGrid(west = 170.0, south = -5.0, east = -170.0, north = 5.0)
        val features = polygons(grid)
        assertEquals(576, features.length())
        for (index in 0 until features.length()) {
            val points = ring(features, index)
            for (vertex in 0 until 4) {
                val longitude = points.getJSONArray(vertex).getDouble(0)
                val nextLongitude = points.getJSONArray(vertex + 1).getDouble(0)
                assertTrue(longitude in 170.0..190.0)
                assertTrue("A local subcell must never span the planet", abs(nextLongitude - longitude) < 1.0)
            }
        }
    }

    @Test fun incompleteGridDoesNotInventAnOverlay() {
        assertEquals(0, polygons(validGrid().copy(points = validGrid().points.take(48))).length())
    }
}
