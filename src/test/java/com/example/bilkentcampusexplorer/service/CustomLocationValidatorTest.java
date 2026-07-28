package com.example.bilkentcampusexplorer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.bilkentcampusexplorer.model.CustomLocationRequest;

class CustomLocationValidatorTest {

    private final CustomLocationValidator validator = new CustomLocationValidator();

    @ParameterizedTest
    @ValueSource(strings = { "Campus Cafe", "  Campus Cafe  " })
    void returnsTrimmedRequiredName(String name) {
        assertThat(validator.requireName(request(name, "", "Point", point())))
                .isEqualTo("Campus Cafe");
    }

    @Test
    void acceptsOneHundredCharacterName() {
        String name = "n".repeat(100);

        assertThat(validator.requireName(request(name, "", "Point", point())))
                .isEqualTo(name);
    }

    @Test
    void rejectsNullRequestWhenNameIsRequired() {
        assertBadRequest(() -> validator.requireName(null), "name is required");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t\n" })
    void rejectsMissingName(String name) {
        assertBadRequest(() -> validator.requireName(request(name, "", "Point", point())),
                "name is required");
    }

    @Test
    void rejectsNameLongerThanOneHundredCharacters() {
        assertBadRequest(
                () -> validator.requireName(request("n".repeat(101), "", "Point", point())),
                "100 characters or fewer");
    }

    @ParameterizedTest
    @ValueSource(strings = { "Campus landmark", "  Campus landmark  " })
    void returnsTrimmedDescription(String description) {
        assertThat(validator.requireDescription(request("Name", description, "Point", point())))
                .isEqualTo("Campus landmark");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void acceptsMissingDescriptionAsEmpty(String description) {
        assertThat(validator.requireDescription(request("Name", description, "Point", point())))
                .isEmpty();
    }

    @Test
    void acceptsFiveHundredCharacterDescription() {
        String description = "d".repeat(500);

        assertThat(validator.requireDescription(request("Name", description, "Point", point())))
                .isEqualTo(description);
    }

    @Test
    void rejectsDescriptionLongerThanFiveHundredCharacters() {
        assertBadRequest(
                () -> validator.requireDescription(request("Name", "d".repeat(501), "Point", point())),
                "500 characters or fewer");
    }

    @ParameterizedTest(name = "accepts valid {0} geometry")
    @MethodSource("validGeometries")
    void validatesEverySupportedGeometryType(String geometryType, List<List<Double>> coordinates) {
        CustomGeometry geometry = validator.validateGeometry(
                request("Name", "Description", geometryType, coordinates));

        assertThat(geometry.type()).isEqualTo(geometryType);
        assertThat(geometry.coordinates()).containsExactlyElementsOf(coordinates);
    }

    @Test
    void rejectsNullRequestWhenValidatingGeometry() {
        assertBadRequest(() -> validator.validateGeometry(null), "geometry type is invalid");
    }

    @ParameterizedTest
    @MethodSource("invalidGeometryTypes")
    void rejectsMissingOrUnsupportedGeometryType(String geometryType) {
        assertBadRequest(
                () -> validator.validateGeometry(request("Name", "", geometryType, point())),
                "geometry type is invalid");
    }

    @ParameterizedTest(name = "rejects invalid coordinate count for {0}")
    @MethodSource("invalidCoordinateCounts")
    void rejectsNullOrInvalidCoordinateCounts(String geometryType, List<List<Double>> coordinates) {
        assertBadRequest(
                () -> validator.validateGeometry(request("Name", "", geometryType, coordinates)),
                "coordinates are invalid");
    }

    @ParameterizedTest(name = "rejects malformed coordinate: {0}")
    @MethodSource("malformedCoordinates")
    void rejectsMalformedCoordinate(String description, List<Double> coordinate) {
        List<List<Double>> coordinates = Arrays.asList(coordinate);

        assertBadRequest(
                () -> validator.validateGeometry(request("Name", "", "Point", coordinates)),
                "coordinates are invalid");
    }

    @ParameterizedTest(name = "rejects out-of-range coordinate: {0}")
    @MethodSource("invalidNumericCoordinates")
    void rejectsNonFiniteOrOutOfRangeCoordinate(String description, List<Double> coordinate) {
        assertBadRequest(
                () -> validator.validateGeometry(request("Name", "", "Point", List.of(coordinate))),
                "coordinates are invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = { "Circle", "Polygon" })
    void rejectsOpenAreaRing(String geometryType) {
        List<List<Double>> openRing = List.of(
                coordinate(32.0, 39.0),
                coordinate(33.0, 39.0),
                coordinate(33.0, 40.0),
                coordinate(32.0, 40.0));

        assertBadRequest(
                () -> validator.validateGeometry(request("Name", "", geometryType, openRing)),
                "closed ring");
    }

    @ParameterizedTest(name = "accepts coordinate boundary {0}")
    @MethodSource("boundaryCoordinates")
    void acceptsCoordinateBoundaries(String description, List<Double> coordinate) {
        CustomGeometry geometry = validator.validateGeometry(
                request("Name", "", "Point", List.of(coordinate)));

        assertThat(geometry.coordinates()).containsExactly(coordinate);
    }

    private static Stream<Arguments> validGeometries() {
        List<List<Double>> line = List.of(coordinate(32.0, 39.0), coordinate(33.0, 40.0));
        List<List<Double>> area = closedRing();
        return Stream.of(
                Arguments.of("Point", point()),
                Arguments.of("LineString", line),
                Arguments.of("Freehand", List.of(
                        coordinate(32.0, 39.0), coordinate(32.5, 39.5), coordinate(33.0, 40.0))),
                Arguments.of("Polygon", area),
                Arguments.of("Circle", area));
    }

    private static Stream<Arguments> invalidGeometryTypes() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of("Rectangle"));
    }

    private static Stream<Arguments> invalidCoordinateCounts() {
        return Stream.of(
                Arguments.of("Point", null),
                Arguments.of("Point", List.of()),
                Arguments.of("Point", List.of(coordinate(32.0, 39.0), coordinate(33.0, 40.0))),
            Arguments.of("Polygon", null),
                Arguments.of("LineString", List.of(coordinate(32.0, 39.0))),
                Arguments.of("LineString", List.of(
                        coordinate(32.0, 39.0), coordinate(33.0, 40.0), coordinate(34.0, 41.0))),
                Arguments.of("Freehand", List.of(coordinate(32.0, 39.0))),
                Arguments.of("Polygon", List.of(
                        coordinate(32.0, 39.0), coordinate(33.0, 39.0), coordinate(32.0, 39.0))),
                Arguments.of("Circle", List.of(
                        coordinate(32.0, 39.0), coordinate(33.0, 39.0), coordinate(32.0, 39.0))));
    }

    private static Stream<Arguments> malformedCoordinates() {
        return Stream.of(
                Arguments.of("null coordinate", null),
                Arguments.of("one value", List.of(32.0)),
                Arguments.of("three values", List.of(32.0, 39.0, 1.0)),
                Arguments.of("null longitude", Arrays.asList(null, 39.0)),
                Arguments.of("null latitude", Arrays.asList(32.0, null)));
    }

    private static Stream<Arguments> invalidNumericCoordinates() {
        return Stream.of(
                Arguments.of("NaN longitude", coordinate(Double.NaN, 39.0)),
                Arguments.of("positive infinite longitude", coordinate(Double.POSITIVE_INFINITY, 39.0)),
                Arguments.of("negative infinite longitude", coordinate(Double.NEGATIVE_INFINITY, 39.0)),
                Arguments.of("NaN latitude", coordinate(32.0, Double.NaN)),
                Arguments.of("positive infinite latitude", coordinate(32.0, Double.POSITIVE_INFINITY)),
                Arguments.of("negative infinite latitude", coordinate(32.0, Double.NEGATIVE_INFINITY)),
                Arguments.of("longitude below minimum", coordinate(-180.1, 39.0)),
                Arguments.of("longitude above maximum", coordinate(180.1, 39.0)),
                Arguments.of("latitude below minimum", coordinate(32.0, -90.1)),
                Arguments.of("latitude above maximum", coordinate(32.0, 90.1)));
    }

    private static Stream<Arguments> boundaryCoordinates() {
        return Stream.of(
                Arguments.of("minimum longitude", coordinate(-180.0, 0.0)),
                Arguments.of("maximum longitude", coordinate(180.0, 0.0)),
                Arguments.of("minimum latitude", coordinate(0.0, -90.0)),
                Arguments.of("maximum latitude", coordinate(0.0, 90.0)));
    }

    private static CustomLocationRequest request(
            String name, String description, String geometryType, List<List<Double>> coordinates) {
        return new CustomLocationRequest(name, description, geometryType, coordinates);
    }

    private static List<List<Double>> point() {
        return List.of(coordinate(32.0, 39.0));
    }

    private static List<List<Double>> closedRing() {
        return List.of(
                coordinate(32.0, 39.0),
                coordinate(33.0, 39.0),
                coordinate(33.0, 40.0),
                coordinate(32.0, 39.0));
    }

    private static List<Double> coordinate(double longitude, double latitude) {
        return List.of(longitude, latitude);
    }

    private static void assertBadRequest(ThrowingCall call, String expectedReason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains(expectedReason);
                });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}