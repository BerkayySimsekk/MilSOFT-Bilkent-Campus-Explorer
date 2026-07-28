package com.example.bilkentcampusexplorer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.bilkentcampusexplorer.model.CustomLocationRequest;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    private static final List<String> CAMPUS_FILES = List.of(
            "building.geojson",
            "food.geojson",
            "health.geojson",
            "parking.geojson",
            "transport.geojson",
            "services.geojson",
            "recreation.geojson",
            "culture.geojson");

    @Mock
    ResourceLoader resourceLoader;

    @Mock
    CustomLocationStore customLocationStore;

    @Mock
    CustomLocationValidator customLocationValidator;

    @Mock
    GeoJsonFeatureFactory geoJsonFeatureFactory;

    LocationService service;

    @BeforeEach
    void setUp() {
        service = new LocationService(
                resourceLoader, customLocationStore, customLocationValidator, geoJsonFeatureFactory);
    }

    @ParameterizedTest
    @ValueSource(doubles = { 14.9, 15.0 })
    void zoomAtOrBelowThresholdReturnsEmptyCollectionWithoutReadingResources(double zoom) {
        Map<String, Object> empty = featureCollection();
        when(customLocationStore.emptyFeatureCollection()).thenReturn(empty);

        Map<String, Object> result = service.getLocations(zoom);

        assertThat(result).isSameAs(empty);
        verify(customLocationStore).emptyFeatureCollection();
        verify(customLocationStore, never()).readCustomLocations();
        verifyNoInteractions(resourceLoader);
    }

    @ParameterizedTest(name = "loads and merges locations at zoom {0}")
    @NullSource
    @ValueSource(doubles = 16.0)
    void nullOrHighZoomLoadsAllCampusAndCustomLocations(Double zoom) throws IOException {
        stubFeatureAccess();
        ByteArrayResource campusResource = new ByteArrayResource("{}".getBytes());
        when(resourceLoader.getResource(anyString())).thenReturn(campusResource);
        when(customLocationStore.readFeatureCollection(any(InputStream.class), anyString()))
                .thenAnswer(invocation -> featureCollection(feature(
                        "campus-" + invocation.getArgument(1, String.class))));
        when(customLocationStore.readCustomLocations())
                .thenReturn(featureCollection(feature("custom-one")));

        Map<String, Object> result = service.getLocations(zoom);
        List<String> featureIds = customLocationStore.getFeatures(result).stream()
            .map(entry -> (String) ((Map<?, ?>) entry).get("id"))
            .toList();

        assertThat(featureIds).containsExactly(
                        "campus-building.geojson",
                        "campus-food.geojson",
                        "campus-health.geojson",
                        "campus-parking.geojson",
                        "campus-transport.geojson",
                        "campus-services.geojson",
                        "campus-recreation.geojson",
                        "campus-culture.geojson",
                        "custom-one");
        for (String campusFile : CAMPUS_FILES) {
            verify(resourceLoader).getResource("classpath:data/" + campusFile);
            verify(customLocationStore).readFeatureCollection(any(InputStream.class),
                    org.mockito.ArgumentMatchers.eq(campusFile));
        }
    }

    @Test
    void wrapsIOExceptionFromCampusResource() throws IOException {
        stubFeatureAccess();
        Resource failingResource = org.mockito.Mockito.mock(Resource.class);
        when(resourceLoader.getResource("classpath:data/building.geojson")).thenReturn(failingResource);
        IOException failure = new IOException("unreadable resource");
        when(failingResource.getInputStream()).thenThrow(failure);

        assertThatThrownBy(() -> service.getLocations(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not read GeoJSON location files.")
                .hasCause(failure);
    }

    @Test
    void addCustomLocationValidatesAppendsPersistsAndReturnsGeneratedFeature() {
        CustomLocationRequest request = new CustomLocationRequest(
                "  Cafe  ", "  Open late  ", "Point", List.of(List.of(32.0, 39.0)));
        CustomGeometry geometry = new CustomGeometry("Point", List.of(List.of(32.0, 39.0)));
        Map<String, Object> existingFeature = feature("custom-existing");
        Map<String, Object> generatedFeature = feature("custom-generated");
        Map<String, Object> existingCollection = featureCollection(existingFeature);
        when(customLocationValidator.requireName(request)).thenReturn("Cafe");
        when(customLocationValidator.requireDescription(request)).thenReturn("Open late");
        when(customLocationValidator.validateGeometry(request)).thenReturn(geometry);
        when(customLocationStore.readCustomLocations()).thenReturn(existingCollection);
        when(customLocationStore.getFeatures(existingCollection))
                .thenReturn(new ArrayList<>(List.of(existingFeature)));
        when(geoJsonFeatureFactory.createCustomFeature("Cafe", "Open late", geometry))
                .thenReturn(generatedFeature);

        Map<String, Object> result = service.addCustomLocation(request);

        assertThat(result).isSameAs(generatedFeature);
        verify(customLocationValidator).requireName(request);
        verify(customLocationValidator).requireDescription(request);
        verify(customLocationValidator).validateGeometry(request);
        verify(geoJsonFeatureFactory).createCustomFeature("Cafe", "Open late", geometry);
        ArgumentCaptor<Map<String, Object>> persisted = mapCaptor();
        verify(customLocationStore).writeCustomLocations(persisted.capture());
        assertThat(featuresFrom(persisted.getValue()))
            .containsExactly(existingFeature, generatedFeature);
    }

    @Test
    void clearCustomLocationsWritesNewEmptyCollection() {
        Map<String, Object> empty = featureCollection();
        when(customLocationStore.emptyFeatureCollection()).thenReturn(empty);

        service.clearCustomLocations();

        verify(customLocationStore).writeCustomLocations(empty);
    }

    @Test
    void deleteCustomLocationRemovesMatchAndPreservesOtherFeatures() {
        Map<String, Object> target = feature("custom-target");
        Map<String, Object> retained = feature("custom-retained");
        Map<String, Object> collection = featureCollection(target, retained);
        when(customLocationStore.readCustomLocations()).thenReturn(collection);
        when(customLocationStore.getFeatures(collection)).thenReturn(List.of(target, retained));

        service.deleteCustomLocation("custom-target");

        ArgumentCaptor<Map<String, Object>> persisted = mapCaptor();
        verify(customLocationStore).writeCustomLocations(persisted.capture());
        assertThat(featuresFrom(persisted.getValue())).containsExactly(retained);
    }

    @Test
    void missingCustomLocationRaisesNotFoundWithoutWriting() {
        Map<String, Object> existing = feature("custom-existing");
        Map<String, Object> collection = featureCollection(existing);
        when(customLocationStore.readCustomLocations()).thenReturn(collection);
        when(customLocationStore.getFeatures(collection)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.deleteCustomLocation("custom-missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason()).contains("not found");
                });
        verify(customLocationStore, never()).writeCustomLocations(anyMap());
    }

    @Test
    void deletionRetainsMalformedEntriesWithoutUnexpectedExceptions() {
        Map<String, Object> target = feature("custom-target");
        Map<String, Object> missingId = Map.of("type", "Feature");
        Map<String, Object> nullId = new LinkedHashMap<>();
        nullId.put("type", "Feature");
        nullId.put("id", null);
        Map<String, Object> numericId = Map.of("type", "Feature", "id", 42);
        List<Object> originalFeatures = Arrays.asList(
                target, null, "not-a-map", missingId, nullId, numericId);
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        collection.put("features", originalFeatures);
        when(customLocationStore.readCustomLocations()).thenReturn(collection);
        when(customLocationStore.getFeatures(collection)).thenReturn(originalFeatures);

        service.deleteCustomLocation("custom-target");

        ArgumentCaptor<Map<String, Object>> persisted = mapCaptor();
        verify(customLocationStore).writeCustomLocations(persisted.capture());
        assertThat(featuresFrom(persisted.getValue()))
            .containsExactly(null, "not-a-map", missingId, nullId, numericId);
    }

    private void stubFeatureAccess() {
        when(customLocationStore.emptyFeatureCollection()).thenAnswer(invocation -> featureCollection());
        when(customLocationStore.getFeatures(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> collection = invocation.getArgument(0);
            return new ArrayList<>((List<?>) collection.get("features"));
        });
    }

    private static Map<String, Object> featureCollection(Map<String, Object>... features) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        collection.put("features", new ArrayList<>(List.of(features)));
        return collection;
    }

    private static Map<String, Object> feature(String id) {
        return Map.of("type", "Feature", "id", id);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> featuresFrom(Map<String, Object> collection) {
        return (List<Object>) collection.get("features");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}