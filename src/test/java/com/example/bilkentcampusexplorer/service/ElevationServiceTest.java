package com.example.bilkentcampusexplorer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.bilkentcampusexplorer.model.ElevationResponse;
import com.example.bilkentcampusexplorer.model.TerrainPathRequest;
import com.example.bilkentcampusexplorer.model.TerrainPathResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ElevationServiceTest {

    private static final double EARTH_RADIUS_METRES = 6_371_008.8;

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsRealSampleForCoordinate() throws IOException {
        ElevationService service = createService();

        ElevationResponse response = service.getElevation(32.05, 39.95);

        assertThat(response.longitude()).isEqualTo(32.05);
        assertThat(response.latitude()).isEqualTo(39.95);
        assertThat(response.elevation()).isEqualTo(100);
        assertThat(response.unit()).isEqualTo("metres");
    }

    @Test
    void rejectsInvalidCoordinatesBeforeLoadingDataset() {
        ElevationService service = createMissingService();

        assertLookupFailure(service, 181.0, 39.9, HttpStatus.BAD_REQUEST, "Longitude must be between");
    }

    @Test
    void reportsCoordinatesOutsideCoverage() throws IOException {
        ElevationService service = createService();

        assertLookupFailure(service, 33.0, 39.95, HttpStatus.NOT_FOUND, "outside the elevation dataset");
    }

    @Test
    void reportsNoDataSamples() throws IOException {
        ElevationService service = createService();

        assertLookupFailure(service, 32.15, 39.95, HttpStatus.NOT_FOUND, "contains no elevation data");
    }

    @Test
    void reportsMissingDatasetWithoutFailingApplicationStartup() {
        ElevationService service = createMissingService();

        assertLookupFailure(service, 32.05, 39.95, HttpStatus.SERVICE_UNAVAILABLE,
                "Elevation dataset is not configured");
    }

        @Test
        void reportsMissingSampleWhenConfigurationExists() throws IOException {
        Path configPath = writeConfiguration(validConfigurationJson());
        ElevationService service = createService(
            configPath, temporaryDirectory.resolve("missing-samples.f32"));

        assertDatasetUnavailable(service, "Expected " + ElevationService.CONFIG_RESOURCE
            + " and " + ElevationService.SAMPLE_DATA_RESOURCE);
        }

        @Test
        void reportsMissingConfigurationWhenSampleExists() throws IOException {
        Path samplePath = writeSampleBytes(new byte[16]);
        ElevationService service = createService(
            temporaryDirectory.resolve("missing-config.json"), samplePath);

        assertDatasetUnavailable(service, "Expected " + ElevationService.CONFIG_RESOURCE
            + " and " + ElevationService.SAMPLE_DATA_RESOURCE);
        }

        @Test
        void malformedConfigurationIsReportedAsUnavailable() throws IOException {
        Path configPath = writeConfiguration("{not-json");
        Path samplePath = writeSampleBytes(new byte[16]);
        ElevationService service = createService(configPath, samplePath);

        assertDatasetUnavailable(service, null);
        }

        @ParameterizedTest(name = "rejects invalid elevation configuration: {0}")
        @MethodSource("invalidConfigurations")
        void invalidConfigurationIsReportedAsUnavailable(String description, String configurationJson)
            throws IOException {
        Path configPath = writeConfiguration(configurationJson);
        Path samplePath = writeSampleBytes(new byte[16]);
        ElevationService service = createService(configPath, samplePath);

        assertDatasetUnavailable(service,
            "Elevation configuration is invalid or is not a north-up raster.");
        }

        @Test
        void corruptedSampleFileSizeIsReportedAsUnavailable() throws IOException {
        Path configPath = writeConfiguration(validConfigurationJson());
        Path samplePath = writeSampleBytes(new byte[12]);
        ElevationService service = createService(configPath, samplePath);

        assertDatasetUnavailable(service,
            "Elevation sample file size does not match its configuration.");
        }

        @Test
        void failedDatasetLoadIsCachedWithoutRetryingRepairedFiles() throws IOException {
        ObjectNode invalidConfiguration = validConfiguration();
        invalidConfiguration.put("width", 0);
        Path configPath = writeConfiguration(invalidConfiguration.toString());
        Path samplePath = writeSampleBytes(new byte[16]);
        ElevationService service = createService(configPath, samplePath);

        ResponseStatusException firstFailure = assertDatasetUnavailable(service,
            "Elevation configuration is invalid or is not a north-up raster.");
        Files.writeString(configPath, validConfigurationJson());

        ResponseStatusException cachedFailure = assertDatasetUnavailable(service,
            "Elevation configuration is invalid or is not a north-up raster.");
        assertThat(cachedFailure.getReason()).isEqualTo(firstFailure.getReason());
        }

    @Test
    void flatGridProducesShortestDiagonalRoute() throws IOException {
    ElevationService service = createService(3, 3, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 100, 100,
        100, 100, 100,
        100, 100, 100);

    TerrainPathResponse response = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9995, 32.0025, 39.9975));

    assertThat(response.type()).isEqualTo("Feature");
    assertThat(response.geometry().type()).isEqualTo("LineString");
    assertThat(response.geometry().coordinates()).containsExactly(
        java.util.List.of(32.0005, 39.9995),
        java.util.List.of(32.0015, 39.9985),
        java.util.List.of(32.0025, 39.9975));
    assertThat(response.properties().segmentCount()).isEqualTo(2);
    assertThat(response.properties().distance3DMetres()).isPositive();
    assertThat(response.properties().startElevationMetres()).isEqualTo(100);
    assertThat(response.properties().endElevationMetres()).isEqualTo(100);
    }

    @Test
    void missingAvoidanceBarriersPreserveExistingShortestRoute() throws IOException {
    ElevationService service = createFlatService(3, 3);

    TerrainPathResponse response = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9995, 32.0025, 39.9975));

    assertThat(response.geometry().coordinates()).containsExactly(
        List.of(32.0005, 39.9995),
        List.of(32.0015, 39.9985),
        List.of(32.0025, 39.9975));
    }

    @Test
    void nullAvoidanceBarriersAreTreatedAsNoBarriers() throws IOException {
    ElevationService service = createFlatService(3, 3);

    TerrainPathResponse response = service.getShortestTerrainPath(
        request(32.0005, 39.9995, 32.0025, 39.9975, null));

    assertThat(response.properties().segmentCount()).isEqualTo(2);
    }

    @Test
    void emptyAvoidanceBarriersPreserveExistingShortestRoute() throws IOException {
    ElevationService service = createFlatService(3, 3);

    TerrainPathResponse response = service.getShortestTerrainPath(
        request(32.0005, 39.9995, 32.0025, 39.9975, List.of()));

    assertThat(response.properties().segmentCount()).isEqualTo(2);
    }

    @Test
    void nullMaximumSlopeAndExistingBarrierConstructorPreserveExistingRoute() throws IOException {
    ElevationService service = createFlatService(3, 3);
    TerrainPathRequest request = new TerrainPathRequest(
        32.0005, 39.9995, 32.0025, 39.9975, null, List.of());
    TerrainPathRequest existingBarrierRequest = new TerrainPathRequest(
        32.0005, 39.9995, 32.0025, 39.9975, List.of());

    TerrainPathResponse nullSlopeResponse = service.getShortestTerrainPath(request);
    TerrainPathResponse existingConstructorResponse = service.getShortestTerrainPath(existingBarrierRequest);

    assertThat(nullSlopeResponse.geometry().coordinates()).containsExactly(
        List.of(32.0005, 39.9995),
        List.of(32.0015, 39.9985),
        List.of(32.0025, 39.9975));
    assertThat(existingConstructorResponse.geometry().coordinates())
        .isEqualTo(nullSlopeResponse.geometry().coordinates());
    assertThat(existingBarrierRequest.maximumSlopeDegrees()).isNull();
    }

    @Test
    void oneBarrierCausesAStarToTakeDetour() throws IOException {
    ElevationService service = createFlatService(3, 3);

    TerrainPathResponse response = service.getShortestTerrainPath(request(
        32.0005, 39.9985, 32.0025, 39.9985,
        List.of(line(32.001, 39.99825, 32.001, 39.99875))));

    assertThat(response.geometry().coordinates()).hasSize(3);
    assertThat(response.geometry().coordinates()).doesNotContain(List.of(32.0015, 39.9985));
    }

    @Test
    void multipleBarriersAreAllEnforced() throws IOException {
    ElevationService service = createFlatService(3, 3);

    TerrainPathResponse response = service.getShortestTerrainPath(request(
        32.0005, 39.9985, 32.0025, 39.9985,
        List.of(
            line(32.001, 39.99825, 32.001, 39.99875),
            line(32.002, 39.99875, 32.002, 39.99925))));

    assertThat(response.geometry().coordinates()).contains(List.of(32.0015, 39.9975));
    assertThat(response.geometry().coordinates()).doesNotContain(List.of(32.0015, 39.9995));
    }

    @Test
    void barrierSpanningTraversableRasterMakesDestinationUnreachable() throws IOException {
    ElevationService service = createFlatService(3, 3);

    assertPathFailure(service, request(
        32.0005, 39.9985, 32.0025, 39.9985,
        List.of(line(32.001, 39.997, 32.001, 40.0))),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void selectedStartDirectlyOnBarrierIsRejected() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0001, 39.9999, 32.0015, 39.9995,
        List.of(line(32.0, 39.9999, 32.0002, 39.9999))),
        HttpStatus.BAD_REQUEST, "selected start lies on an avoidance barrier");
    }

    @Test
    void selectedDestinationDirectlyOnBarrierIsRejected() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0019, 39.9999,
        List.of(line(32.0018, 39.9999, 32.002, 39.9999))),
        HttpStatus.BAD_REQUEST, "selected destination lies on an avoidance barrier");
    }

    @Test
    void snappedStartCellCentreDirectlyOnBarrierIsRejected() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0001, 39.9999, 32.0015, 39.9995,
        List.of(line(32.0004, 39.9995, 32.0006, 39.9995))),
        HttpStatus.BAD_REQUEST, "snapped start raster-cell centre");
    }

    @Test
    void snappedDestinationCellCentreDirectlyOnBarrierIsRejected() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0019, 39.9999,
        List.of(line(32.0014, 39.9995, 32.0016, 39.9995))),
        HttpStatus.BAD_REQUEST, "snapped destination raster-cell centre");
    }

    @Test
    void horizontalTransitionCannotCrossBarrierBetweenCellCentres() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0015, 39.9995,
        List.of(line(32.001, 39.9994, 32.001, 39.9996))),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void verticalTransitionCannotCrossBarrierBetweenCellCentres() throws IOException {
    ElevationService service = createFlatService(1, 2);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0005, 39.9985,
        List.of(line(32.0004, 39.999, 32.0006, 39.999))),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void diagonalTransitionCannotCrossBarrierBetweenCellCentres() throws IOException {
    ElevationService service = createFlatService(2, 2);

    TerrainPathResponse response = service.getShortestTerrainPath(request(
        32.0005, 39.9995, 32.0015, 39.9985,
        List.of(line(32.0009, 39.9989, 32.0011, 39.9991))));

    assertThat(response.properties().segmentCount()).isEqualTo(2);
    }

    @Test
    void touchingOnlyBarrierEndpointStillRejectsTransition() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0015, 39.9995,
        List.of(line(32.001, 39.9995, 32.001, 39.9997))),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void touchingLastBarrierEndpointStillRejectsTransition() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0015, 39.9995,
        List.of(line(32.001, 39.9997, 32.001, 39.9995))),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void transitionCannotEndOnBarrierInterior() throws IOException {
    ElevationService service = createFlatService(3, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0025, 39.9995,
        List.of(line(32.0015, 39.9993, 32.0015, 39.9997))),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void collinearOverlapRejectsTransition() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0015, 39.9995,
        List.of(line(32.0008, 39.9995, 32.0012, 39.9995))),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void openLineStringIsAcceptedAndNotAutomaticallyClosed() throws IOException {
    ElevationService service = createFlatService(1, 3);
    List<List<Double>> openBarrier = List.of(
        List.of(32.0, 39.999),
        List.of(32.0005, 40.001),
        List.of(32.001, 39.999));

    TerrainPathResponse response = service.getShortestTerrainPath(request(
        32.0005, 39.9995, 32.0005, 39.9975, List.of(openBarrier)));

    assertThat(response.properties().segmentCount()).isEqualTo(2);
    }

    @Test
    void malformedBarrierCoordinatesAreRejected() throws IOException {
    ElevationService service = createFlatService(2, 1);
    List<List<List<List<Double>>>> malformedBarrierCases = List.of(
        java.util.Arrays.asList((List<List<Double>>) null),
        List.of(List.of(List.of(32.0), List.of(32.001, 39.999))),
        List.of(List.of(List.of(32.0, 39.999, 5.0), List.of(32.001, 39.999))),
        List.of(line(181.0, 39.999, 32.001, 39.999))
    );

    for (List<List<List<Double>>> barriers : malformedBarrierCases) {
        assertPathFailure(service, request(32.0005, 39.9995, 32.0015, 39.9995, barriers),
            HttpStatus.BAD_REQUEST, "Avoidance barrier");
    }
    }

    @Test
    void nonFiniteBarrierCoordinatesAreRejected() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0015, 39.9995,
        List.of(line(Double.NaN, 39.999, 32.001, 39.999))),
        HttpStatus.BAD_REQUEST, "finite longitude and latitude");
    }

    @Test
    void barrierWithFewerThanTwoDistinctCoordinatesIsRejected() throws IOException {
    ElevationService service = createFlatService(2, 1);

    assertPathFailure(service, request(
        32.0005, 39.9995, 32.0015, 39.9995,
        List.of(List.of(
            List.of(32.001, 39.999),
            List.of(32.001, 39.999),
            List.of(32.001, 39.999)))),
        HttpStatus.BAD_REQUEST, "at least two distinct coordinates");
    }

    @Test
    void highCentralElevationProducesShorterThreeDimensionalDetour() throws IOException {
    ElevationService service = createService(3, 3, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 100, 100,
        100, 10_000, 100,
        100, 100, 100);

    TerrainPathResponse response = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9985, 32.0025, 39.9985));

    assertThat(response.geometry().coordinates()).hasSize(3);
    assertThat(response.geometry().coordinates().get(1)).isNotEqualTo(java.util.List.of(32.0015, 39.9985));
    double directHorizontalDistance = haversineDistance(32.0005, 39.9985, 32.0025, 39.9985);
    assertThat(response.properties().distance3DMetres()).isGreaterThan(directHorizontalDistance);
    assertThat(response.properties().distance3DMetres()).isLessThan(1_000);
    }

    @Test
    void totalDistanceEqualsSumOfRouteSegmentCosts() throws IOException {
    ElevationService service = createService(1, 3, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 110, 125);

    TerrainPathResponse response = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9995, 32.0005, 39.9975));

    double firstHorizontalDistance = haversineDistance(32.0005, 39.9995, 32.0005, 39.9985);
    double secondHorizontalDistance = haversineDistance(32.0005, 39.9985, 32.0005, 39.9975);
    double expectedDistance = Math.hypot(firstHorizontalDistance, 10)
        + Math.hypot(secondHorizontalDistance, 15);
    assertThat(response.properties().distance3DMetres()).isCloseTo(expectedDistance, within(1e-6));
    }

    @Test
    void maximumSlopeIsAHardTransitionRestrictionAndExactThresholdIsAllowed() throws IOException {
    ElevationService service = createService(2, 1,
        new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 }, 100, 150);
    double horizontalDistance = haversineDistance(32.0005, 39.9995, 32.0015, 39.9995);
    double transitionSlope = Math.toDegrees(Math.atan2(50, horizontalDistance));

    TerrainPathResponse unrestricted = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9995));
    TerrainPathResponse aboveThreshold = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9995, transitionSlope + 0.1));
    TerrainPathResponse exactThreshold = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9995, transitionSlope));

    assertThat(unrestricted.properties().segmentCount()).isEqualTo(1);
    assertThat(aboveThreshold.properties().segmentCount()).isEqualTo(1);
    assertThat(exactThreshold.properties().segmentCount()).isEqualTo(1);
    assertPathFailure(service,
        new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9995, transitionSlope - 0.1),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void rejectsInvalidMaximumSlopesBeforeLoadingDataset() {
    ElevationService service = createMissingService();

    for (double invalidSlope : new double[] {
            -0.1, 90.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
        assertPathFailure(service,
            new TerrainPathRequest(32.0, 39.9, 32.1, 39.8, invalidSlope),
            HttpStatus.BAD_REQUEST, "Maximum slope must be a finite number between 0 and 90 degrees");
    }
    }

    @Test
    void acceptsMaximumSlopeBoundaryAndDecimalValues() throws IOException {
    ElevationService service = createFlatService(2, 1);

    for (double maximumSlope : new double[] { 0, 12.5, 90 }) {
        TerrainPathResponse response = service.getShortestTerrainPath(
            new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9995, maximumSlope));
        assertThat(response.properties().segmentCount()).isEqualTo(1);
    }
    }

    @Test
    void maximumSlopeRestrictsAscendingAndDescendingTransitionsEqually() throws IOException {
    ElevationService service = createService(2, 1,
        new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 }, 100, 200);
    double horizontalDistance = haversineDistance(32.0005, 39.9995, 32.0015, 39.9995);
    double maximumSlope = Math.toDegrees(Math.atan2(100, horizontalDistance)) - 0.1;

    assertPathFailure(service,
        new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9995, maximumSlope),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    assertPathFailure(service,
        new TerrainPathRequest(32.0015, 39.9995, 32.0005, 39.9995, maximumSlope),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void maximumSlopeCausesLongerValidDetour() throws IOException {
    ElevationService service = createService(3, 3,
        new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 100, 100,
        100, 300, 100,
        100, 100, 100);

    TerrainPathResponse response = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9985, 32.0025, 39.9985, 10.0));

    assertThat(response.properties().segmentCount()).isEqualTo(2);
    assertThat(response.geometry().coordinates()).doesNotContain(List.of(32.0015, 39.9985));
    List<List<Double>> coordinates = response.geometry().coordinates();
    for (int index = 1; index < coordinates.size(); index++) {
        List<Double> first = coordinates.get(index - 1);
        List<Double> second = coordinates.get(index);
        double horizontalDistance = haversineDistance(
            first.get(0), first.get(1), second.get(0), second.get(1));
        double firstElevation = first.equals(List.of(32.0015, 39.9985)) ? 300 : 100;
        double secondElevation = second.equals(List.of(32.0015, 39.9985)) ? 300 : 100;
        double slope = Math.toDegrees(Math.atan2(
            Math.abs(secondElevation - firstElevation), horizontalDistance));
        assertThat(slope).isLessThanOrEqualTo(10.0);
    }
    }

    @Test
    void diagonalSlopeUsesActualDiagonalHorizontalDistance() throws IOException {
    ElevationService service = createService(2, 2,
        new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 200,
        200, 200);
    double diagonalDistance = haversineDistance(32.0005, 39.9995, 32.0015, 39.9985);
    double horizontalCellDistance = haversineDistance(32.0005, 39.9995, 32.0015, 39.9995);
    double diagonalSlope = Math.toDegrees(Math.atan2(100, diagonalDistance));
    double horizontalSlope = Math.toDegrees(Math.atan2(100, horizontalCellDistance));
    double maximumSlope = (diagonalSlope + horizontalSlope) / 2;

    TerrainPathResponse response = service.getShortestTerrainPath(
        new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9985, maximumSlope));

    assertThat(diagonalSlope).isLessThan(maximumSlope);
    assertThat(maximumSlope).isLessThan(horizontalSlope);
    assertThat(response.geometry().coordinates()).containsExactly(
        List.of(32.0005, 39.9995), List.of(32.0015, 39.9985));
    }

    @Test
    void maximumSlopeAndAvoidanceBarrierAreEnforcedTogether() throws IOException {
    ElevationService service = createService(3, 3,
        new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 100, 100,
        100, 300, 100,
        100, 100, 100);
    List<List<Double>> upperBarrier = line(32.0, 39.999, 32.003, 39.999);

    TerrainPathResponse response = service.getShortestTerrainPath(new TerrainPathRequest(
        32.0005, 39.9985, 32.0025, 39.9985, 10.0, List.of(upperBarrier)));

    assertThat(response.geometry().coordinates()).contains(List.of(32.0015, 39.9975));
    assertThat(response.geometry().coordinates()).doesNotContain(List.of(32.0015, 39.9985));
    assertThat(response.geometry().coordinates()).doesNotContain(List.of(32.0015, 39.9995));
    }

    @Test
    void combinedMaximumSlopeAndBarriersCanMakeDestinationUnreachable() throws IOException {
    ElevationService service = createService(3, 3,
        new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 100, 100,
        100, 300, 100,
        100, 100, 100);
    List<List<List<Double>>> barriers = List.of(
        line(32.0, 39.999, 32.003, 39.999),
        line(32.0, 39.998, 32.003, 39.998));

    assertPathFailure(service, new TerrainPathRequest(
        32.0005, 39.9985, 32.0025, 39.9985, 10.0, barriers),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void noDataBarrierMakesDestinationUnreachable() throws IOException {
    ElevationService service = createService(3, 3, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, Float.NaN, 100,
        100, Float.NaN, 100,
        100, Float.NaN, 100);

    assertPathFailure(service, new TerrainPathRequest(32.0005, 39.9985, 32.0025, 39.9985),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void diagonalMoveCannotCutAcrossNoDataCorners() throws IOException {
    ElevationService service = createService(2, 2, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, Float.NaN,
        Float.NaN, 100);

    assertPathFailure(service, new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9985),
        HttpStatus.NOT_FOUND, "No traversable terrain path");
    }

    @Test
    void rejectsStartOrDestinationOnNoDataCell() throws IOException {
    ElevationService service = createService(3, 1, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        Float.NaN, 100, Float.NaN);

    assertPathFailure(service, new TerrainPathRequest(32.0005, 39.9995, 32.0015, 39.9995),
        HttpStatus.NOT_FOUND, "contains no elevation data");
    assertPathFailure(service, new TerrainPathRequest(32.0015, 39.9995, 32.0025, 39.9995),
        HttpStatus.NOT_FOUND, "contains no elevation data");
    }

    @Test
    void rejectsCoordinatesOutsideRaster() throws IOException {
    ElevationService service = createService(2, 1, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 100);

    assertPathFailure(service, new TerrainPathRequest(31.0, 39.9995, 32.0015, 39.9995),
        HttpStatus.NOT_FOUND, "outside the elevation dataset");
    }

    @Test
    void rejectsInvalidPathCoordinatesBeforeLoadingDataset() {
    ElevationService service = createMissingService();
    TerrainPathRequest[] invalidRequests = {
        new TerrainPathRequest(null, 39.9, 32.0, 39.8),
        new TerrainPathRequest(32.0, Double.NaN, 32.1, 39.8),
        new TerrainPathRequest(32.0, 39.9, Double.POSITIVE_INFINITY, 39.8),
        new TerrainPathRequest(32.0, 39.9, 32.1, 91.0)
    };

    for (TerrainPathRequest request : invalidRequests) {
        assertPathFailure(service, request, HttpStatus.BAD_REQUEST, "Longitude must be between");
    }
    }

    @Test
    void rejectsPointsThatSnapToSameCell() throws IOException {
    ElevationService service = createService(2, 1, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
        100, 100);

    assertPathFailure(service, new TerrainPathRequest(32.0001, 39.9999, 32.0009, 39.9991),
        HttpStatus.BAD_REQUEST, "different elevation raster cells");
    }

    @Test
    void shortestPathReportsMissingDataset() {
    ElevationService service = createMissingService();

    assertPathFailure(service, new TerrainPathRequest(32.0, 39.9, 32.1, 39.8),
        HttpStatus.SERVICE_UNAVAILABLE, "Elevation dataset is not configured");
    }

    private ElevationService createService() throws IOException {
        return createService(2, 2, new double[] { 32.0, 0.1, 0.0, 40.0, 0.0, -0.1 },
            100, Float.NaN, 200, 300);
    }

    private ElevationService createFlatService(int width, int height) throws IOException {
        float[] elevations = new float[width * height];
        java.util.Arrays.fill(elevations, 100);
        return createService(width, height, new double[] { 32.0, 0.001, 0.0, 40.0, 0.0, -0.001 },
            elevations);
    }

    private ElevationService createService(int width, int height, double[] geoTransform, float... elevations)
        throws IOException {
        Path configPath = temporaryDirectory.resolve("elevation-config-" + width + "x" + height + ".json");
        Path samplePath = temporaryDirectory.resolve("bilkent-elevation-" + width + "x" + height + ".f32");
        Files.writeString(configPath, new ObjectMapper().writeValueAsString(java.util.Map.of(
            "width", width,
            "height", height,
            "geoTransform", geoTransform,
            "unit", "metres")));

        ByteBuffer samples = ByteBuffer.allocate(elevations.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float elevation : elevations) {
            samples.putFloat(elevation);
        }
        Files.write(samplePath, samples.array());

        return new ElevationService(
                new FileSystemResource(configPath),
                new FileSystemResource(samplePath),
                new ObjectMapper());
    }

    private ElevationService createMissingService() {
        return new ElevationService(
                new FileSystemResource(temporaryDirectory.resolve("missing-config.json")),
                new FileSystemResource(temporaryDirectory.resolve("missing-samples.f32")),
                new ObjectMapper());
    }

        private ElevationService createService(Path configPath, Path samplePath) {
        return new ElevationService(
            new FileSystemResource(configPath),
            new FileSystemResource(samplePath),
            new ObjectMapper());
        }

        private Path writeConfiguration(String configurationJson) throws IOException {
        Path configPath = temporaryDirectory.resolve("arbitrary-elevation-config.json");
        Files.writeString(configPath, configurationJson);
        return configPath;
        }

        private Path writeSampleBytes(byte[] sampleBytes) throws IOException {
        Path samplePath = temporaryDirectory.resolve("arbitrary-elevation-samples.f32");
        Files.write(samplePath, sampleBytes);
        return samplePath;
        }

        private static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
            invalidConfiguration("zero width", configuration -> configuration.put("width", 0)),
            invalidConfiguration("negative width", configuration -> configuration.put("width", -1)),
            invalidConfiguration("zero height", configuration -> configuration.put("height", 0)),
            invalidConfiguration("negative height", configuration -> configuration.put("height", -1)),
            invalidConfiguration("missing geoTransform", configuration -> configuration.remove("geoTransform")),
            invalidConfiguration("null geoTransform", configuration -> configuration.putNull("geoTransform")),
            invalidConfiguration("short geoTransform",
                configuration -> setGeoTransform(configuration, 32.0, 0.1, 0.0, 40.0, 0.0)),
            invalidConfiguration("long geoTransform",
                configuration -> setGeoTransform(configuration, 32.0, 0.1, 0.0, 40.0, 0.0, -0.1, 1.0)),
            invalidConfiguration("zero horizontal pixel size",
                configuration -> setGeoTransform(configuration, 32.0, 0.0, 0.0, 40.0, 0.0, -0.1)),
            invalidConfiguration("negative horizontal pixel size",
                configuration -> setGeoTransform(configuration, 32.0, -0.1, 0.0, 40.0, 0.0, -0.1)),
            invalidConfiguration("zero vertical pixel size",
                configuration -> setGeoTransform(configuration, 32.0, 0.1, 0.0, 40.0, 0.0, 0.0)),
            invalidConfiguration("positive vertical pixel size",
                configuration -> setGeoTransform(configuration, 32.0, 0.1, 0.0, 40.0, 0.0, 0.1)),
            invalidConfiguration("nonzero horizontal rotation",
                configuration -> setGeoTransform(configuration, 32.0, 0.1, 0.01, 40.0, 0.0, -0.1)),
            invalidConfiguration("nonzero vertical rotation",
                configuration -> setGeoTransform(configuration, 32.0, 0.1, 0.0, 40.0, 0.01, -0.1)),
            invalidConfiguration("missing unit", configuration -> configuration.remove("unit")),
            invalidConfiguration("null unit", configuration -> configuration.putNull("unit")),
            invalidConfiguration("blank unit", configuration -> configuration.put("unit", "   ")));
        }

        private static Arguments invalidConfiguration(
            String description, Consumer<ObjectNode> mutation) {
        ObjectNode configuration = validConfiguration();
        mutation.accept(configuration);
        return Arguments.of(description, configuration.toString());
        }

        private static ObjectNode validConfiguration() {
        ObjectNode configuration = new ObjectMapper().createObjectNode();
        configuration.put("width", 2);
        configuration.put("height", 2);
        setGeoTransform(configuration, 32.0, 0.1, 0.0, 40.0, 0.0, -0.1);
        configuration.put("unit", "metres");
        return configuration;
        }

        private static String validConfigurationJson() {
        return validConfiguration().toString();
        }

        private static void setGeoTransform(ObjectNode configuration, double... values) {
        var geoTransform = configuration.putArray("geoTransform");
        for (double value : values) {
            geoTransform.add(value);
        }
        }

        private static ResponseStatusException assertDatasetUnavailable(
            ElevationService service, String expectedDetail) {
        ResponseStatusException exception = catchThrowableOfType(
            () -> service.getElevation(32.05, 39.95),
            ResponseStatusException.class);
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exception.getReason()).contains("Elevation dataset is not configured");
        if (expectedDetail != null) {
            assertThat(exception.getReason()).contains(expectedDetail);
        }
        return exception;
        }

    private static void assertLookupFailure(ElevationService service, double longitude, double latitude,
            HttpStatus expectedStatus, String expectedMessage) {
        assertThatThrownBy(() -> service.getElevation(longitude, latitude))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(expectedStatus);
                    assertThat(exception.getReason()).contains(expectedMessage);
                });
    }

    private static void assertPathFailure(ElevationService service, TerrainPathRequest request,
            HttpStatus expectedStatus, String expectedMessage) {
        assertThatThrownBy(() -> service.getShortestTerrainPath(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(expectedStatus);
                    assertThat(exception.getReason()).contains(expectedMessage);
                });
    }

    private static TerrainPathRequest request(
            double startLongitude,
            double startLatitude,
            double endLongitude,
            double endLatitude,
            List<List<List<Double>>> avoidanceBarriers) {
        return new TerrainPathRequest(
            startLongitude, startLatitude, endLongitude, endLatitude, avoidanceBarriers);
    }

    private static List<List<Double>> line(
            double startLongitude,
            double startLatitude,
            double endLongitude,
            double endLatitude) {
        return List.of(
            List.of(startLongitude, startLatitude),
            List.of(endLongitude, endLatitude));
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
}