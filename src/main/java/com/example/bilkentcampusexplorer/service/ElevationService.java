package com.example.bilkentcampusexplorer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.bilkentcampusexplorer.model.ElevationResponse;
import com.example.bilkentcampusexplorer.model.TerrainPathRequest;
import com.example.bilkentcampusexplorer.model.TerrainPathResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ElevationService {

    static final String CONFIG_RESOURCE = "classpath:static/data/elevation/elevation-config.json";
    static final String SAMPLE_DATA_RESOURCE = "classpath:static/data/elevation/bilkent-elevation.f32";
    private static final double EARTH_RADIUS_METRES = 6_371_008.8;
    private static final double GEOMETRY_EPSILON = 1e-10;
    private static final int[] NEIGHBOUR_ROW_OFFSETS = { -1, -1, -1, 0, 0, 1, 1, 1 };
    private static final int[] NEIGHBOUR_COLUMN_OFFSETS = { -1, 0, 1, -1, 1, -1, 0, 1 };

    private final Resource configResource;
    private final Resource sampleDataResource;
    private final ObjectMapper objectMapper;

    private ElevationDataset elevationDataset;
    private String datasetLoadFailure;
    private boolean datasetLoadAttempted;

    @Autowired
    public ElevationService(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this(resourceLoader.getResource(CONFIG_RESOURCE), resourceLoader.getResource(SAMPLE_DATA_RESOURCE), objectMapper);
    }

    ElevationService(Resource configResource, Resource sampleDataResource, ObjectMapper objectMapper) {
        this.configResource = configResource;
        this.sampleDataResource = sampleDataResource;
        this.objectMapper = objectMapper;
    }

    public ElevationResponse getElevation(Double longitude, Double latitude) {
        validateCoordinates(longitude, latitude);
        ElevationDataset dataset = getDataset();
        Float elevation = dataset.getElevation(longitude, latitude);

        if (elevation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The coordinate is outside the elevation dataset or contains no elevation data.");
        }

        return new ElevationResponse(longitude, latitude, elevation.doubleValue(), dataset.configuration().unit());
    }

    public TerrainPathResponse getShortestTerrainPath(TerrainPathRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A terrain path request body is required.");
        }

        validateCoordinates(request.startLongitude(), request.startLatitude());
        validateCoordinates(request.endLongitude(), request.endLatitude());
        validateMaximumSlope(request.maximumSlopeDegrees());
        List<BarrierSegment> avoidanceSegments = prepareAvoidanceBarriers(request.avoidanceBarriers());
        Coordinate selectedStart = new Coordinate(request.startLongitude(), request.startLatitude());
        Coordinate selectedEnd = new Coordinate(request.endLongitude(), request.endLatitude());
        rejectPointOnBarrier(selectedStart, avoidanceSegments, "The selected start lies on an avoidance barrier.");
        rejectPointOnBarrier(selectedEnd, avoidanceSegments, "The selected destination lies on an avoidance barrier.");
        ElevationDataset dataset = getDataset();
        RasterCell start = dataset.getCell(request.startLongitude(), request.startLatitude());
        RasterCell end = dataset.getCell(request.endLongitude(), request.endLatitude());

        if (start == null || end == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The start or destination is outside the elevation dataset or contains no elevation data.");
        }
        if (start.id() == end.id()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The start and destination must snap to different elevation raster cells.");
        }
        rejectPointOnBarrier(dataset.getCellCentreCoordinate(start.id()), avoidanceSegments,
            "The snapped start raster-cell centre lies on an avoidance barrier.");
        rejectPointOnBarrier(dataset.getCellCentreCoordinate(end.id()), avoidanceSegments,
            "The snapped destination raster-cell centre lies on an avoidance barrier.");

        PathResult result = dataset.findShortestPath(
            start, end, request.maximumSlopeDegrees(), avoidanceSegments);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No traversable terrain path exists between the selected points.");
        }

        List<List<Double>> coordinates = result.cellIds().stream()
                .map(dataset::getCellCentre)
                .toList();
        return new TerrainPathResponse(
                "Feature",
                new TerrainPathResponse.Geometry("LineString", coordinates),
                new TerrainPathResponse.Properties(
                        result.distance3DMetres(),
                        coordinates.size() - 1,
                        start.elevation(),
                        end.elevation(),
                        coordinates.getFirst(),
                        coordinates.getLast()));
    }

    private static void validateCoordinates(Double longitude, Double latitude) {
        if (longitude == null || latitude == null
                || !Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180
                || latitude < -90 || latitude > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Longitude must be between -180 and 180 and latitude must be between -90 and 90.");
        }
    }

    private static void validateMaximumSlope(Double maximumSlopeDegrees) {
        if (maximumSlopeDegrees != null
                && (!Double.isFinite(maximumSlopeDegrees)
                        || maximumSlopeDegrees < 0
                        || maximumSlopeDegrees > 90)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Maximum slope must be a finite number between 0 and 90 degrees.");
        }
    }

    private static List<BarrierSegment> prepareAvoidanceBarriers(
            List<List<List<Double>>> avoidanceBarriers) {
        if (avoidanceBarriers == null || avoidanceBarriers.isEmpty()) {
            return List.of();
        }

        List<BarrierSegment> segments = new ArrayList<>();
        for (int barrierIndex = 0; barrierIndex < avoidanceBarriers.size(); barrierIndex++) {
            List<List<Double>> barrier = avoidanceBarriers.get(barrierIndex);
            if (barrier == null) {
                throw invalidBarrier(barrierIndex, "must be a non-null coordinate array");
            }

            Coordinate previous = null;
            int distinctCoordinateCount = 0;
            for (int coordinateIndex = 0; coordinateIndex < barrier.size(); coordinateIndex++) {
                Coordinate coordinate = validateBarrierCoordinate(
                        barrier.get(coordinateIndex), barrierIndex, coordinateIndex);
                if (previous == null || !coordinatesEqual(previous, coordinate)) {
                    distinctCoordinateCount += 1;
                    if (previous != null) {
                        segments.add(new BarrierSegment(previous, coordinate));
                    }
                    previous = coordinate;
                }
            }

            if (distinctCoordinateCount < 2) {
                throw invalidBarrier(barrierIndex, "must contain at least two distinct coordinates");
            }
        }
        return List.copyOf(segments);
    }

    private static Coordinate validateBarrierCoordinate(
            List<Double> coordinate,
            int barrierIndex,
            int coordinateIndex) {
        if (coordinate == null || coordinate.size() != 2) {
            throw invalidBarrier(barrierIndex,
                    "coordinate " + coordinateIndex + " must contain exactly longitude and latitude");
        }

        Double longitude = coordinate.get(0);
        Double latitude = coordinate.get(1);
        if (longitude == null || latitude == null
                || !Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180
                || latitude < -90 || latitude > 90) {
            throw invalidBarrier(barrierIndex,
                    "coordinate " + coordinateIndex + " must contain finite longitude and latitude values in range");
        }
        return new Coordinate(longitude, latitude);
    }

    private static ResponseStatusException invalidBarrier(int barrierIndex, String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Avoidance barrier " + barrierIndex + " " + detail + ".");
    }

    private static void rejectPointOnBarrier(
            Coordinate coordinate,
            List<BarrierSegment> avoidanceSegments,
            String message) {
        if (avoidanceSegments.stream().anyMatch(segment -> pointOnSegment(coordinate, segment))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static boolean intersectsAnyBarrier(
            Coordinate start,
            Coordinate end,
            List<BarrierSegment> avoidanceSegments) {
        BarrierSegment movement = new BarrierSegment(start, end);
        return avoidanceSegments.stream().anyMatch(barrier -> segmentsIntersect(movement, barrier));
    }

    private static boolean segmentsIntersect(BarrierSegment first, BarrierSegment second) {
        if (!boundingBoxesIntersect(first, second)) {
            return false;
        }

        double firstStartSide = orientation(first.start(), first.end(), second.start());
        double firstEndSide = orientation(first.start(), first.end(), second.end());
        double secondStartSide = orientation(second.start(), second.end(), first.start());
        double secondEndSide = orientation(second.start(), second.end(), first.end());

        if (isNearZero(firstStartSide, first.start(), first.end(), second.start())
                && pointOnSegment(second.start(), first)) {
            return true;
        }
        if (isNearZero(firstEndSide, first.start(), first.end(), second.end())
                && pointOnSegment(second.end(), first)) {
            return true;
        }
        if (isNearZero(secondStartSide, second.start(), second.end(), first.start())
                && pointOnSegment(first.start(), second)) {
            return true;
        }
        if (isNearZero(secondEndSide, second.start(), second.end(), first.end())
                && pointOnSegment(first.end(), second)) {
            return true;
        }

        return oppositeSides(firstStartSide, firstEndSide) && oppositeSides(secondStartSide, secondEndSide);
    }

    private static boolean boundingBoxesIntersect(BarrierSegment first, BarrierSegment second) {
        return Math.max(first.minimumLongitude(), second.minimumLongitude())
                        <= Math.min(first.maximumLongitude(), second.maximumLongitude()) + GEOMETRY_EPSILON
                && Math.max(first.minimumLatitude(), second.minimumLatitude())
                        <= Math.min(first.maximumLatitude(), second.maximumLatitude()) + GEOMETRY_EPSILON;
    }

    private static boolean pointOnSegment(Coordinate point, BarrierSegment segment) {
        return point.longitude() >= segment.minimumLongitude() - GEOMETRY_EPSILON
                && point.longitude() <= segment.maximumLongitude() + GEOMETRY_EPSILON
                && point.latitude() >= segment.minimumLatitude() - GEOMETRY_EPSILON
                && point.latitude() <= segment.maximumLatitude() + GEOMETRY_EPSILON
                && isNearZero(orientation(segment.start(), segment.end(), point),
                        segment.start(), segment.end(), point);
    }

    private static double orientation(Coordinate start, Coordinate end, Coordinate point) {
        return (end.longitude() - start.longitude()) * (point.latitude() - start.latitude())
                - (end.latitude() - start.latitude()) * (point.longitude() - start.longitude());
    }

    private static boolean isNearZero(
            double value,
            Coordinate start,
            Coordinate end,
            Coordinate point) {
        double scale = Math.max(
                Math.hypot(end.longitude() - start.longitude(), end.latitude() - start.latitude()),
                Math.hypot(point.longitude() - start.longitude(), point.latitude() - start.latitude()));
        return Math.abs(value) <= GEOMETRY_EPSILON * Math.max(scale, GEOMETRY_EPSILON);
    }

    private static boolean oppositeSides(double first, double second) {
        return (first < 0 && second > 0) || (first > 0 && second < 0);
    }

    private static boolean coordinatesEqual(Coordinate first, Coordinate second) {
        return Math.abs(first.longitude() - second.longitude()) <= GEOMETRY_EPSILON
                && Math.abs(first.latitude() - second.latitude()) <= GEOMETRY_EPSILON;
    }

    private synchronized ElevationDataset getDataset() {
        if (elevationDataset != null) {
            return elevationDataset;
        }
        if (datasetLoadAttempted) {
            throw datasetUnavailable();
        }

        datasetLoadAttempted = true;
        try {
            elevationDataset = loadDataset();
            return elevationDataset;
        } catch (IOException | IllegalArgumentException | ArithmeticException exception) {
            datasetLoadFailure = exception.getMessage();
            throw datasetUnavailable();
        }
    }

    private ElevationDataset loadDataset() throws IOException {
        if (!configResource.exists() || !sampleDataResource.exists()) {
            throw new IOException("Expected " + CONFIG_RESOURCE + " and " + SAMPLE_DATA_RESOURCE + ".");
        }

        ElevationConfiguration configuration;
        try (InputStream configInputStream = configResource.getInputStream()) {
            configuration = objectMapper.readValue(configInputStream, ElevationConfiguration.class);
        }
        validateConfiguration(configuration);

        byte[] sampleBytes;
        try (InputStream sampleInputStream = sampleDataResource.getInputStream()) {
            sampleBytes = sampleInputStream.readAllBytes();
        }

        int sampleCount = Math.multiplyExact(configuration.width(), configuration.height());
        int expectedByteCount = Math.multiplyExact(sampleCount, Float.BYTES);
        if (sampleBytes.length != expectedByteCount) {
            throw new IOException("Elevation sample file size does not match its configuration.");
        }

        ByteBuffer samples = ByteBuffer.wrap(sampleBytes).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        return new ElevationDataset(configuration, samples);
    }

    private static void validateConfiguration(ElevationConfiguration configuration) {
        double[] geoTransform = configuration.geoTransform();
        if (configuration.width() <= 0 || configuration.height() <= 0
                || geoTransform == null || geoTransform.length != 6
                || !Double.isFinite(geoTransform[0]) || !Double.isFinite(geoTransform[1])
                || !Double.isFinite(geoTransform[3]) || !Double.isFinite(geoTransform[5])
                || geoTransform[1] <= 0 || geoTransform[5] >= 0
                || geoTransform[2] != 0 || geoTransform[4] != 0
                || configuration.unit() == null || configuration.unit().isBlank()) {
            throw new IllegalArgumentException("Elevation configuration is invalid or is not a north-up raster.");
        }
    }

    private ResponseStatusException datasetUnavailable() {
        String detail = datasetLoadFailure == null ? "Elevation files could not be loaded." : datasetLoadFailure;
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Elevation dataset is not configured. " + detail);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ElevationConfiguration(int width, int height, double[] geoTransform, String unit) {
    }

    private record ElevationDataset(ElevationConfiguration configuration, ByteBuffer samples) {

        private Float getElevation(double longitude, double latitude) {
            RasterCell cell = getCell(longitude, latitude);
            return cell == null ? null : cell.elevation();
        }

        private RasterCell getCell(double longitude, double latitude) {
            double[] geoTransform = configuration.geoTransform();
            double determinant = geoTransform[1] * geoTransform[5] - geoTransform[2] * geoTransform[4];
            double longitudeOffset = longitude - geoTransform[0];
            double latitudeOffset = latitude - geoTransform[3];
            int column = (int) Math.floor(
                    (longitudeOffset * geoTransform[5] - latitudeOffset * geoTransform[2]) / determinant);
            int row = (int) Math.floor(
                    (latitudeOffset * geoTransform[1] - longitudeOffset * geoTransform[4]) / determinant);

            if (column < 0 || column >= configuration.width() || row < 0 || row >= configuration.height()) {
                return null;
            }

            int sampleIndex = row * configuration.width() + column;
            float elevation = samples.getFloat(sampleIndex * Float.BYTES);
            return Float.isFinite(elevation) ? new RasterCell(row, column, sampleIndex, elevation) : null;
        }

        private PathResult findShortestPath(
            RasterCell start,
            RasterCell end,
            Double maximumSlopeDegrees,
            List<BarrierSegment> avoidanceSegments) {
            int cellCount = configuration.width() * configuration.height();
            double[] bestKnownCosts = new double[cellCount];
            Arrays.fill(bestKnownCosts, Double.POSITIVE_INFINITY);
            int[] predecessors = new int[cellCount];
            Arrays.fill(predecessors, -1);
            boolean[] closed = new boolean[cellCount];
            PriorityQueue<SearchEntry> openSet = new PriorityQueue<>(Comparator
                    .comparingDouble(SearchEntry::estimatedTotalCost)
                    .thenComparingInt(SearchEntry::cellId));

            bestKnownCosts[start.id()] = 0;
            openSet.add(new SearchEntry(start.id(), 0, heuristic(start, end)));

            while (!openSet.isEmpty()) {
                SearchEntry currentEntry = openSet.remove();
                int currentId = currentEntry.cellId();
                if (currentEntry.costFromStart() > bestKnownCosts[currentId] || closed[currentId]) {
                    continue;
                }
                if (currentId == end.id()) {
                    return reconstructPath(start.id(), end.id(), predecessors, bestKnownCosts[end.id()]);
                }

                closed[currentId] = true;
                RasterCell current = getCell(currentId);
                for (int neighbourIndex = 0; neighbourIndex < NEIGHBOUR_ROW_OFFSETS.length; neighbourIndex++) {
                    int rowOffset = NEIGHBOUR_ROW_OFFSETS[neighbourIndex];
                    int columnOffset = NEIGHBOUR_COLUMN_OFFSETS[neighbourIndex];
                    RasterCell neighbour = getCell(current.row() + rowOffset, current.column() + columnOffset);
                    if (neighbour == null || closed[neighbour.id()]
                            || isBlockedDiagonal(current, rowOffset, columnOffset)
                            || intersectsAnyBarrier(
                                getCellCentreCoordinate(current.id()),
                                getCellCentreCoordinate(neighbour.id()),
                                avoidanceSegments)) {
                        continue;
                    }

                        double horizontalDistance = horizontalDistance(current, neighbour);
                        if (exceedsMaximumSlope(
                            current, neighbour, horizontalDistance, maximumSlopeDegrees)) {
                        continue;
                        }

                        double candidateCost = bestKnownCosts[currentId]
                            + distance3D(current, neighbour, horizontalDistance);
                    if (candidateCost >= bestKnownCosts[neighbour.id()]) {
                        continue;
                    }

                    bestKnownCosts[neighbour.id()] = candidateCost;
                    predecessors[neighbour.id()] = currentId;
                    openSet.add(new SearchEntry(
                            neighbour.id(),
                            candidateCost,
                            candidateCost + heuristic(neighbour, end)));
                }
            }

            return null;
        }

        private boolean isBlockedDiagonal(RasterCell current, int rowOffset, int columnOffset) {
            return rowOffset != 0 && columnOffset != 0
                    && (getCell(current.row() + rowOffset, current.column()) == null
                            || getCell(current.row(), current.column() + columnOffset) == null);
        }

        private RasterCell getCell(int row, int column) {
            if (row < 0 || row >= configuration.height() || column < 0 || column >= configuration.width()) {
                return null;
            }

            int cellId = row * configuration.width() + column;
            float elevation = samples.getFloat(cellId * Float.BYTES);
            return Float.isFinite(elevation) ? new RasterCell(row, column, cellId, elevation) : null;
        }

        private RasterCell getCell(int cellId) {
            return getCell(cellId / configuration.width(), cellId % configuration.width());
        }

        private List<Double> getCellCentre(int cellId) {
            Coordinate coordinate = getCellCentreCoordinate(cellId);
            return List.of(coordinate.longitude(), coordinate.latitude());
        }

        private Coordinate getCellCentreCoordinate(int cellId) {
            RasterCell cell = getCell(cellId);
            double[] geoTransform = configuration.geoTransform();
            double longitude = geoTransform[0]
                    + (cell.column() + 0.5) * geoTransform[1]
                    + (cell.row() + 0.5) * geoTransform[2];
            double latitude = geoTransform[3]
                    + (cell.column() + 0.5) * geoTransform[4]
                    + (cell.row() + 0.5) * geoTransform[5];
                return new Coordinate(longitude, latitude);
        }

        private double horizontalDistance(RasterCell first, RasterCell second) {
            Coordinate firstCentre = getCellCentreCoordinate(first.id());
            Coordinate secondCentre = getCellCentreCoordinate(second.id());
            return haversineDistance(
                    firstCentre.longitude(), firstCentre.latitude(),
                    secondCentre.longitude(), secondCentre.latitude());
        }

        private double slopeDegrees(
                RasterCell first,
                RasterCell second,
                double horizontalDistance) {
            double elevationDifference = Math.abs(second.elevation() - first.elevation());
            return Math.toDegrees(Math.atan2(elevationDifference, horizontalDistance));
        }

        private boolean exceedsMaximumSlope(
                RasterCell first,
                RasterCell second,
                double horizontalDistance,
                Double maximumSlopeDegrees) {
            if (!Double.isFinite(horizontalDistance) || horizontalDistance <= 0) {
                return true;
            }
            return maximumSlopeDegrees != null
                    && slopeDegrees(first, second, horizontalDistance) > maximumSlopeDegrees;
        }

        private double distance3D(RasterCell first, RasterCell second) {
            return distance3D(first, second, horizontalDistance(first, second));
        }

        private double distance3D(
                RasterCell first,
                RasterCell second,
                double horizontalDistance) {
            double elevationDifference = second.elevation() - first.elevation();
            return Math.hypot(horizontalDistance, elevationDifference);
        }

        private double heuristic(RasterCell current, RasterCell destination) {
            return distance3D(current, destination);
        }

        private static PathResult reconstructPath(
                int startId,
                int endId,
                int[] predecessors,
                double distance3DMetres) {
            List<Integer> cellIds = new ArrayList<>();
            int currentId = endId;
            while (currentId != -1) {
                cellIds.add(currentId);
                if (currentId == startId) {
                    Collections.reverse(cellIds);
                    return new PathResult(cellIds, distance3DMetres);
                }
                currentId = predecessors[currentId];
            }
            return null;
        }
    }

    private static double haversineDistance(
            double firstLongitude,
            double firstLatitude,
            double secondLongitude,
            double secondLatitude) {
        double latitudeDelta = Math.toRadians(secondLatitude - firstLatitude);
        double longitudeDelta = Math.toRadians(secondLongitude - firstLongitude);
        double firstLatitudeRadians = Math.toRadians(firstLatitude);
        double secondLatitudeRadians = Math.toRadians(secondLatitude);
        double haversine = Math.pow(Math.sin(latitudeDelta / 2), 2)
                + Math.cos(firstLatitudeRadians) * Math.cos(secondLatitudeRadians)
                        * Math.pow(Math.sin(longitudeDelta / 2), 2);
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1, Math.sqrt(haversine)));
    }

    private record RasterCell(int row, int column, int id, float elevation) {
    }

    private record Coordinate(double longitude, double latitude) {
    }

    private record BarrierSegment(
            Coordinate start,
            Coordinate end,
            double minimumLongitude,
            double minimumLatitude,
            double maximumLongitude,
            double maximumLatitude) {

        private BarrierSegment(Coordinate start, Coordinate end) {
            this(
                    start,
                    end,
                    Math.min(start.longitude(), end.longitude()),
                    Math.min(start.latitude(), end.latitude()),
                    Math.max(start.longitude(), end.longitude()),
                    Math.max(start.latitude(), end.latitude()));
        }
    }

    private record SearchEntry(int cellId, double costFromStart, double estimatedTotalCost) {
    }

    private record PathResult(List<Integer> cellIds, double distance3DMetres) {
    }
}