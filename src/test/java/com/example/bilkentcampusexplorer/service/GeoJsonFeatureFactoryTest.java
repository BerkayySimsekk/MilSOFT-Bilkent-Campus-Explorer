package com.example.bilkentcampusexplorer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GeoJsonFeatureFactoryTest {

    private final GeoJsonFeatureFactory factory = new GeoJsonFeatureFactory();

    @ParameterizedTest(name = "creates valid GeoJSON for {0}")
    @MethodSource("geometryCases")
    void createsFeatureForEverySupportedGeometryType(
            String customType,
            List<List<Double>> inputCoordinates,
            String expectedGeoJsonType,
            Object expectedGeoJsonCoordinates,
            String expectedCustomFeatureType) {
        Map<String, Object> feature = factory.createCustomFeature(
                "Campus place", "Useful description", new CustomGeometry(customType, inputCoordinates));

        assertThat(feature.get("type")).isEqualTo("Feature");
        assertThat(feature.get("id")).isInstanceOfSatisfying(String.class, id -> {
            assertThat(id).startsWith("custom-");
            assertThat(id).isNotBlank();
        });

        assertThat(feature.get("properties")).isInstanceOfSatisfying(Map.class, properties -> {
            assertThat(properties)
                    .containsEntry("name", "Campus place")
                    .containsEntry("description", "Useful description")
                    .containsEntry("category", "Custom");
            if (expectedCustomFeatureType == null) {
                assertThat(properties).doesNotContainKey("customFeatureType");
            } else {
                assertThat(properties).containsEntry("customFeatureType", expectedCustomFeatureType);
            }
        });

        assertThat(feature.get("geometry")).isInstanceOfSatisfying(Map.class, geometry -> {
            assertThat(geometry)
                    .containsEntry("type", expectedGeoJsonType)
                    .containsEntry("coordinates", expectedGeoJsonCoordinates);
        });
    }

    @Test
    void generatesDifferentIdsForDifferentFeatures() {
        CustomGeometry geometry = new CustomGeometry("Point", List.of(coordinate(32.0, 39.0)));

        Map<String, Object> first = factory.createCustomFeature("First", "", geometry);
        Map<String, Object> second = factory.createCustomFeature("Second", "", geometry);

        assertThat(first.get("id")).isNotEqualTo(second.get("id"));
    }

    private static Stream<Arguments> geometryCases() {
        List<List<Double>> point = List.of(coordinate(32.0, 39.0));
        List<List<Double>> line = List.of(coordinate(32.0, 39.0), coordinate(33.0, 40.0));
        List<List<Double>> ring = List.of(
                coordinate(32.0, 39.0),
                coordinate(33.0, 39.0),
                coordinate(33.0, 40.0),
                coordinate(32.0, 39.0));
        return Stream.of(
                Arguments.of("Point", point, "Point", point.getFirst(), null),
                Arguments.of("LineString", line, "LineString", line, null),
                Arguments.of("Polygon", ring, "Polygon", List.of(ring), null),
                Arguments.of("Circle", ring, "Polygon", List.of(ring), "Circle"),
                Arguments.of("Freehand", line, "LineString", line, "Freehand"));
    }

    private static List<Double> coordinate(double longitude, double latitude) {
        return List.of(longitude, latitude);
    }
}