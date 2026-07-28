# Bilkent Campus Explorer

Bilkent Campus Explorer is a small, responsive campus map. Its location mode shows mapped Bilkent main-campus locations on an OpenStreetMap base map with searching, filtering, GeoJSON export, drawing, and feature details. Its elevation mode overlays prepared terrain data on the same OpenLayers map, reports longitude, latitude, and elevation for clicked coordinates, and calculates shortest three-dimensional routes across the prepared terrain raster.

## Technology stack

- Java 21
- Spring Boot 3.x and Spring Web
- Maven
- Vanilla HTML, CSS, and JavaScript
- OpenLayers (loaded from a pinned CDN)
- GeoJSON
- OpenStreetMap base-map tiles
- GDAL and NumPy for optional elevation preprocessing

## Project structure

```text
pom.xml
src/main/java/com/example/bilkentcampusexplorer/
  BilkentCampusExplorerApplication.java
src/main/java/com/example/bilkentcampusexplorer/controller/
  LocationController.java
src/main/java/com/example/bilkentcampusexplorer/model/
  TerrainPathRequest.java
  TerrainPathResponse.java
src/main/java/com/example/bilkentcampusexplorer/service/
  ElevationService.java
src/main/resources/data/
  building.geojson
  culture.geojson
  food.geojson
  health.geojson
  parking.geojson
  recreation.geojson
  services.geojson
  transport.geojson
  custom.geojson
src/main/resources/static/
  index.html
  css/style.css
  js/map.js
  js/map-terrain-avoidance.js
  js/map-terrain-path.js
scripts/
  prepare-elevation.py
README.md
```

## Run the application

Run the following command from the project root:

```text
mvn spring-boot:run
```

Then open http://localhost:8080 in a browser.

## API endpoints


- `GET /api/locations` returns one GeoJSON `FeatureCollection` merged from the built-in category files and saved custom Points.
- `GET /api/elevation?longitude=32.7484&latitude=39.8712` returns a real raster elevation sample in metres. It returns `400` for invalid longitude/latitude, `404` outside coverage or on a no-data cell, and `503` until elevation assets have been prepared.
- `POST /api/elevation/shortest-path` accepts JSON coordinates and returns the shortest terrain-grid route as one GeoJSON `Feature`. Request content is `application/json`; response content is `application/geo+json`.
- `POST /api/custom-locations` saves a validated custom Point. Its JSON request body contains `name`, `description`, `longitude`, and `latitude`.
- `DELETE /api/custom-locations` removes every saved custom Point.
- `DELETE /api/custom-locations/{featureId}` removes one saved custom feature by its GeoJSON feature ID.

Custom locations are saved in [src/main/resources/data/custom.geojson](src/main/resources/data/custom.geojson). They remain available after an application restart when running the project from source. Set `app.custom-locations-file` to use a different writable file location.

### Shortest terrain path

Send the selected start and destination in EPSG:4326 longitude/latitude order:

```http
POST /api/elevation/shortest-path
Content-Type: application/json
Accept: application/geo+json
```

```json
{
  "startLongitude": 32.7481,
  "startLatitude": 39.8684,
  "endLongitude": 32.7520,
  "endLatitude": 39.8720,
  "maximumSlopeDegrees": 15,
  "avoidanceBarriers": [
    [
      [32.7490, 39.8690],
      [32.7492, 39.8693],
      [32.7496, 39.8696]
    ],
    [
      [32.7510, 39.8700],
      [32.7514, 39.8704],
      [32.7512, 39.8708]
    ]
  ]
}
```

`maximumSlopeDegrees` is optional and accepts a finite value from `0` through `90`, measured in degrees from horizontal. A missing or `null` value means there is no slope restriction. In the elevation-map controls, **Maximum allowed slope** provides the same setting; leave it blank for no slope limit.

`avoidanceBarriers` is optional. Each entry is one open LineString in EPSG:4326 `[longitude, latitude]` order. A barrier's final coordinate is not connected back to its starting coordinate, and the line does not represent or enclose an area. The browser sends the current barriers with each path request; they are temporary page-session state and are not saved to `custom.geojson`, another file, or a database.

Each selected coordinate snaps to the centre of its containing elevation-raster cell. The response is a GeoJSON `LineString` whose coordinates are all of the traversed cell centres in `[longitude, latitude]` order:

```json
{
  "type": "Feature",
  "geometry": {
    "type": "LineString",
    "coordinates": [
      [32.748194, 39.868472],
      [32.748472, 39.868750],
      [32.748750, 39.869028]
    ]
  },
  "properties": {
    "distance3DMetres": 248.6,
    "segmentCount": 2,
    "startElevationMetres": 1025.0,
    "endElevationMetres": 1041.0,
    "snappedStart": [32.748194, 39.868472],
    "snappedEnd": [32.748750, 39.869028]
  }
}
```

The route is shortest under an 8-connected raster-grid model, not an exact continuous-surface geodesic. A* generates finite north, south, east, west, and diagonal neighbours on demand. Diagonals cannot cross a corner when either adjacent side cell is no-data. Every accepted edge costs the direct three-dimensional distance between cell centres: Haversine horizontal distance in metres combined with the elevation difference using the Pythagorean formula. The heuristic uses the same direct 3D distance to the destination. There is no road preference, smoothing, or interpolation.

When a maximum slope is supplied, A* calculates the average slope separately for every candidate neighboring-cell transition:

```text
slopeDegrees =
  degrees(atan2(
    absolute elevation difference,
    Haversine horizontal distance
  ))
```

Transitions steeper than the threshold are rejected, while transitions exactly equal to it are allowed. This is a hard traversability rule, not an additional A* cost: accepted transitions retain their existing 3D edge cost. The absolute elevation difference restricts ascents and descents equally. Horizontal distance is calculated between the actual raster-cell centres, so diagonal transitions automatically use their longer diagonal distance rather than horizontal/vertical cell spacing or an assumed sample size.

For every candidate A* movement, the segment between the two raster-cell centres is tested for intersection with every segment of every avoidance barrier. A movement is rejected if it crosses, overlaps, or touches a barrier, including a barrier endpoint. The selected endpoints and their snapped raster-cell centres must not lie on a barrier. Barriers have no artificial width or buffer, and no point-in-polygon rule is used because they are open lines rather than enclosed areas. When both a maximum slope and avoidance barriers are supplied, a transition must satisfy both restrictions.

The endpoint returns `400` for invalid or non-finite coordinates and when both points snap to one cell, `404` for coordinates outside the raster, no-data endpoints, or an unreachable destination, and `503` when the prepared dataset cannot be loaded. Error responses are JSON objects with a `message`.

To use the route controls, switch to **Show Elevation Map**. Optionally draw barriers with **Draw barriers to avoid**, and optionally enter a maximum allowed slope in degrees. Select **Find shortest terrain path**, click a start point, and click a destination. The map shows the snapped `S` and `E` cells, every raster transition in the route, and the total 3D distance while enforcing the selected restrictions.

Select **Clear avoidance barriers** to remove every barrier. Press Escape to stop barrier drawing without deleting completed lines. Switching to the location map hides the barrier layer and stops active barrier drawing but keeps completed barriers for the page session; switching back shows them again. Select **Cancel terrain path** or press Escape while choosing points, and select **Clear terrain path** after a result. Switching back to the location map cancels requests and clears the route.

Because a route follows DT2-resolution raster cells without smoothing, it can look angular even when a continuous terrain route would appear smoother. Each slope is the average between two raster samples; terrain variations smaller than the source raster resolution cannot be detected.

## Prepare elevation data

No elevation dataset is committed to this repository. Do not point OpenLayers at a raw DTED file. After obtaining the real DTED/DT0/DT1/DT2 or GeoTIFF that covers Bilkent campus, run the preprocessing script from a Python environment that provides the GDAL Python bindings and NumPy:

```text
python scripts/prepare-elevation.py C:\path\to\real-elevation.dt1
```

The script uses [src/main/resources/static/data/campus-boundary.geojson](src/main/resources/static/data/campus-boundary.geojson) as its crop cutline and creates these files:

```text
src/main/resources/static/data/elevation/bilkent-elevation.tif
src/main/resources/static/data/elevation/bilkent-elevation.f32
src/main/resources/static/data/elevation/elevation-config.json
```

- `bilkent-elevation.tif` is an EPSG:4326 Cloud Optimized GeoTIFF rendered by the persistent OpenLayers elevation layer. OpenLayers reprojects it to the map's EPSG:3857 view while rendering.
- `bilkent-elevation.f32` contains the same north-up raster samples as little-endian 32-bit floats. Spring loads it once and uses its geotransform for coordinate lookups; no-data cells are stored as `NaN`.
- `elevation-config.json` records the raster URL, dimensions, affine geotransform, and real minimum/maximum elevations used by the colour ramp and legend.

The crop uses nearest-neighbour resampling so it does not synthesize intermediate elevation values. Restart Spring Boot after preparing or replacing the assets. Until then, the mode switch and legend remain available but clearly report that no dataset has been prepared.

The equivalent core GDAL operations performed by the script are:

```text
gdalwarp -cutline src/main/resources/static/data/campus-boundary.geojson -crop_to_cutline -t_srs EPSG:4326 -r near -dstnodata -32768 source.dt1 cropped.tif
gdal_translate -of COG -co COMPRESS=DEFLATE -co RESAMPLING=NEAREST cropped.tif bilkent-elevation.tif
```

The script additionally writes the float query grid and configuration required by `GET /api/elevation`.

## Verify

```text
mvn test
mvn package
```

## Request flow

```text
Browser
  ↓
Downloads index.html, style.css, and map.js
  ↓
JavaScript creates the OpenLayers map
  ↓
JavaScript fetches /api/locations
  ↓
Spring Boot returns a GeoJSON FeatureCollection
  ↓
JavaScript passes the GeoJSON to OpenLayers
  ↓
OpenLayers creates Feature objects
  ↓
Features are stored in their category VectorSource
  ↓
Each category has its own VectorLayer
  ↓
The user searches, filters, toggles category visibility, selects, and views location information
```

## Responsibilities

- **Spring Boot** serves both the frontend files and the GeoJSON API from the same origin.
- **JavaScript** performs the HTTP request and applies all search and category filtering locally.
- **OpenLayers** renders one location layer per category, manages layer visibility, view fitting, interaction, styling, and the popup overlay.
- **OpenStreetMap** provides the base-map tiles.
- Each built-in location category is bundled in its own GeoJSON file; custom locations are saved separately rather than in a database.

