# Bilkent Campus Explorer

## Project Description

Bilkent Campus Explorer is a web-based interactive map for exploring Bilkent University’s main campus. It displays campus locations by category, provides search and filtering tools, allows users to create and export custom map features, and includes an elevation mode for inspecting terrain and calculating the shortest terrain-aware path between two points.

The frontend and backend are served together by a Spring Boot application and are available at `http://localhost:8080`.

## Tech Stack

### Backend

* Java 21
* Spring Boot 3.4.5
* Spring Web
* Jackson for JSON and GeoJSON processing
* Maven

### Frontend

* HTML5
* CSS3
* Vanilla JavaScript
* OpenLayers 10.6.1
* OpenStreetMap

### Geospatial Data

* GeoJSON for campus locations and custom features
* GeoTIFF and binary elevation-raster data
* DTED/GDAL and NumPy for elevation-data preprocessing

### Testing and Deployment

* JUnit 5 and AssertJ
* JaCoCo for test coverage
* Docker
* Eclipse Temurin 21 JRE
* GitHub Container Registry

## Main Functionalities

* Display Bilkent campus locations on an interactive OpenStreetMap.
* Organize locations into categories such as buildings, food, health, parking, transport, services, recreation, and culture.
* Search locations by name, category, or description.
* Show or hide individual location categories.
* Filter locations by selecting an area on the map.
* Cluster nearby or overlapping markers and display the locations contained in each cluster.
* View detailed information about a selected location.
* Export an individual location or an entire category as GeoJSON.
* Add custom points, lines, polygons, circles, and freehand features.
* Remove one custom feature or clear all custom features.
* Switch to an elevation map and inspect the longitude, latitude, and elevation of a selected point.
* Calculate the shortest three-dimensional terrain path between two points using A*.
* Apply a maximum-slope restriction to terrain-path calculations.
* Draw temporary avoidance barriers that calculated paths are not allowed to cross.

## Running Without Docker

### Prerequisites

* Java Development Kit (JDK) 21
* Maven 3.9 or later

### Run with Maven

Open a terminal in the project root and run:

```bash
mvn spring-boot:run
```

Then open:

```text
http://localhost:8080
```

### Build and Run the JAR

To build the application:

```bash
mvn clean package
```

Run the generated JAR:

```bash
java -jar target/bilkent-campus-explorer-0.0.1-SNAPSHOT.jar
```

## Running With Docker

Docker must be installed and running.

### Option 1: Use the Prebuilt Image

Pull the image from GitHub Container Registry:

```bash
docker pull ghcr.io/berkayysimsekk/milsoft-bilkent-campus-explorer:sha-fc6e669
```

Run the container:

```bash
docker run --name bilkent-campus-explorer -p 8080:8080 -v bilkent-campus-data:/app/data ghcr.io/berkayysimsekk/milsoft-bilkent-campus-explorer:sha-fc6e669
```

The named Docker volume preserves custom features if the container is removed and recreated.

### Option 2: Build the Docker Image Locally

The included `Dockerfile` copies the packaged JAR, so build the project first:

```bash
mvn clean package
```

Build the Docker image:

```bash
docker build -t bilkent-campus-explorer .
```

Run it:

```bash
docker run --name bilkent-campus-explorer -p 8080:8080 -v bilkent-campus-data:/app/data bilkent-campus-explorer
```

Open `http://localhost:8080` after the application starts.

To stop and start the same container later:

```bash
docker stop bilkent-campus-explorer
docker start bilkent-campus-explorer
```

## Testing

Run all tests:

```bash
mvn test
```

Run the full Maven verification lifecycle and generate the JaCoCo report:

```bash
mvn clean verify
```

The coverage report is generated at:

```text
target/site/jacoco/index.html
```

## Notes

* Custom features are stored in `src/main/resources/data/custom.geojson` when the project is run directly from source.
* Inside Docker, custom features are stored at `/app/data/custom.geojson`.
* The map uses OpenStreetMap tiles and loads OpenLayers-related browser dependencies from external CDNs, so an internet connection is required for the complete interface.
