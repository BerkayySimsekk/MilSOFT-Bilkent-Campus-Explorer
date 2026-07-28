package com.example.bilkentcampusexplorer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class CustomLocationStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingCustomLocationFileReturnsEmptyFeatureCollection() {
        CustomLocationStore store = storeAt(temporaryDirectory.resolve("missing.geojson"));

        Map<String, Object> collection = store.readCustomLocations();

        assertThat(collection.get("type")).isEqualTo("FeatureCollection");
        assertThat(store.getFeatures(collection)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyFeatureCollectionContainsMutableFeaturesList() {
        CustomLocationStore store = storeAt(temporaryDirectory.resolve("custom.geojson"));

        Map<String, Object> collection = store.emptyFeatureCollection();
        List<Object> features = (List<Object>) collection.get("features");
        features.add(feature("custom-one", "First"));

        assertThat(collection.get("type")).isEqualTo("FeatureCollection");
        assertThat(features).hasSize(1);
    }

    @Test
    void readsValidFeatureCollectionAndPreservesFeatures() throws IOException {
        CustomLocationStore store = storeAt(temporaryDirectory.resolve("custom.geojson"));
        String json = """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {"type": "Feature", "id": "campus-one", "properties": {"name": "Library"}}
                  ]
                }
                """;

        Map<String, Object> collection = store.readFeatureCollection(input(json), "campus-test");

        assertThat(collection.get("type")).isEqualTo("FeatureCollection");
        assertThat(store.getFeatures(collection)).singleElement().isInstanceOfSatisfying(Map.class, feature -> {
            assertThat(feature).containsEntry("id", "campus-one");
            assertThat(feature.get("properties")).isEqualTo(Map.of("name", "Library"));
        });
    }

    @Test
    void rejectsObjectWhoseTypeIsNotFeatureCollectionAndNamesSource() {
        CustomLocationStore store = storeAt(temporaryDirectory.resolve("custom.geojson"));

        assertThatThrownBy(() -> store.readFeatureCollection(
                input("{\"type\":\"Feature\",\"features\":[]}"), "buildings"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The buildings GeoJSON file is not a FeatureCollection.");
    }

    @ParameterizedTest(name = "rejects invalid features member: {0}")
    @MethodSource("invalidFeaturesDocuments")
    void rejectsMissingOrNonListFeatures(String description, String json) {
        CustomLocationStore store = storeAt(temporaryDirectory.resolve("custom.geojson"));

        assertThatThrownBy(() -> store.readFeatureCollection(input(json), "custom-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The GeoJSON FeatureCollection has no features array.");
    }

    @Test
    void wrapsInvalidCustomJsonWithUsefulReadError() throws IOException {
        Path customFile = temporaryDirectory.resolve("custom.geojson");
        Files.writeString(customFile, "{not-json", StandardCharsets.UTF_8);
        CustomLocationStore store = storeAt(customFile);

        assertThatThrownBy(store::readCustomLocations)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not read custom GeoJSON locations file.")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void getFeaturesReturnsUsableMutableListWithoutMutatingSource() {
        CustomLocationStore store = storeAt(temporaryDirectory.resolve("custom.geojson"));
        Map<String, Object> original = Map.of(
                "type", "FeatureCollection",
                "features", List.of(feature("custom-one", "First")));

        List<Object> mutableFeatures = store.getFeatures(original);
        mutableFeatures.add(feature("custom-two", "Second"));

        assertThat(mutableFeatures).hasSize(2);
        assertThat((List<?>) original.get("features")).hasSize(1);
    }

    @Test
    void writeCreatesParentsAndProducesReadableValidJson() throws IOException {
        Path customFile = temporaryDirectory.resolve("nested/data/custom.geojson");
        CustomLocationStore store = storeAt(customFile);
        Map<String, Object> collection = featureCollection(feature("custom-one", "First"));

        store.writeCustomLocations(collection);

        assertThat(customFile).exists().isRegularFile();
        JsonNode writtenJson = objectMapper.readTree(customFile.toFile());
        assertThat(writtenJson.path("type").asText()).isEqualTo("FeatureCollection");
        assertThat(writtenJson.path("features").isArray()).isTrue();
        assertThat(writtenJson.path("features").get(0).path("id").asText()).isEqualTo("custom-one");

        Map<String, Object> roundTrip = store.readCustomLocations();
        assertThat(store.getFeatures(roundTrip)).containsExactly(feature("custom-one", "First"));
    }

    @Test
    void rewritingExistingFileReplacesOldContents() {
        Path customFile = temporaryDirectory.resolve("custom.geojson");
        CustomLocationStore store = storeAt(customFile);
        store.writeCustomLocations(featureCollection(feature("custom-old", "Old")));

        store.writeCustomLocations(featureCollection(feature("custom-new", "New")));

        assertThat(store.getFeatures(store.readCustomLocations()))
                .containsExactly(feature("custom-new", "New"));
    }

    @Test
    void writeFailureIsWrappedWhenParentPathIsARegularFile() throws IOException {
        Path blockingFile = temporaryDirectory.resolve("blocking-file");
        Files.writeString(blockingFile, "not a directory", StandardCharsets.UTF_8);
        Path customFile = blockingFile.resolve("custom.geojson");
        CustomLocationStore store = storeAt(customFile);

        assertThatThrownBy(() -> store.writeCustomLocations(
                featureCollection(feature("custom-one", "First"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not save custom GeoJSON locations file.")
                .hasCauseInstanceOf(IOException.class);
        assertThat(blockingFile).isRegularFile();
        assertThat(Files.readString(blockingFile, StandardCharsets.UTF_8)).isEqualTo("not a directory");
    }

    private static Stream<Arguments> invalidFeaturesDocuments() {
        return Stream.of(
                Arguments.of("missing features", "{\"type\":\"FeatureCollection\"}"),
                Arguments.of("object features", "{\"type\":\"FeatureCollection\",\"features\":{}}"));
    }

    private CustomLocationStore storeAt(Path path) {
        return new CustomLocationStore(objectMapper, path.toString());
    }

    private static ByteArrayInputStream input(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> featureCollection(Map<String, Object> feature) {
        return Map.of("type", "FeatureCollection", "features", List.of(feature));
    }

    private static Map<String, Object> feature(String id, String name) {
        return Map.of(
                "type", "Feature",
                "id", id,
                "properties", Map.of("name", name));
    }
}