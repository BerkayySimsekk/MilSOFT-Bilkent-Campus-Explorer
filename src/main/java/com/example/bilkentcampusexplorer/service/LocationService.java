package com.example.bilkentcampusexplorer.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.bilkentcampusexplorer.model.CustomLocationRequest;

@Service
public class LocationService {

    private static final double MIN_LOCATION_ZOOM = 15;
    private static final List<String> CAMPUS_LOCATION_FILES = List.of(
            "building.geojson",
            "food.geojson",
            "health.geojson",
            "parking.geojson",
            "transport.geojson",
            "services.geojson",
            "recreation.geojson",
            "culture.geojson");

    private final ResourceLoader resourceLoader;
    private final CustomLocationStore customLocationStore;
    private final CustomLocationValidator customLocationValidator;
    private final GeoJsonFeatureFactory geoJsonFeatureFactory;

    public LocationService(ResourceLoader resourceLoader, CustomLocationStore customLocationStore,
            CustomLocationValidator customLocationValidator, GeoJsonFeatureFactory geoJsonFeatureFactory) {
        this.resourceLoader = resourceLoader;
        this.customLocationStore = customLocationStore;
        this.customLocationValidator = customLocationValidator;
        this.geoJsonFeatureFactory = geoJsonFeatureFactory;
    }

    public synchronized Map<String, Object> getLocations(Double zoom) {
        if (zoom != null && zoom <= MIN_LOCATION_ZOOM) {
            return customLocationStore.emptyFeatureCollection();
        }

        try {
            Map<String, Object> mergedLocations = customLocationStore.emptyFeatureCollection();
            List<Object> features = customLocationStore.getFeatures(mergedLocations);
            for (String locationFile : CAMPUS_LOCATION_FILES) {
                try (InputStream inputStream = resourceLoader.getResource("classpath:data/" + locationFile).getInputStream()) {
                    features.addAll(customLocationStore.getFeatures(customLocationStore.readFeatureCollection(inputStream, locationFile)));
                }
            }

            Map<String, Object> customLocations = customLocationStore.readCustomLocations();
            features.addAll(customLocationStore.getFeatures(customLocations));
            mergedLocations.put("features", features);
            return mergedLocations;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read GeoJSON location files.", exception);
        }
    }

    public synchronized Map<String, Object> addCustomLocation(CustomLocationRequest request) {
        String name = customLocationValidator.requireName(request);
        String description = customLocationValidator.requireDescription(request);
        CustomGeometry geometry = customLocationValidator.validateGeometry(request);

        Map<String, Object> customLocations = customLocationStore.readCustomLocations();
        List<Object> features = customLocationStore.getFeatures(customLocations);
        Map<String, Object> customFeature = geoJsonFeatureFactory.createCustomFeature(name, description, geometry);
        features.add(customFeature);
        customLocations.put("features", features);
        customLocationStore.writeCustomLocations(customLocations);

        return customFeature;
    }

    public synchronized void clearCustomLocations() {
        customLocationStore.writeCustomLocations(customLocationStore.emptyFeatureCollection());
    }

    public synchronized void deleteCustomLocation(String featureId) {
        Map<String, Object> customLocations = customLocationStore.readCustomLocations();
        List<Object> retainedFeatures = new ArrayList<>();
        boolean wasRemoved = false;

        for (Object feature : customLocationStore.getFeatures(customLocations)) {
            if (featureId.equals(getFeatureId(feature))) {
                wasRemoved = true;
            } else {
                retainedFeatures.add(feature);
            }
        }

        if (!wasRemoved) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Custom location was not found.");
        }

        customLocations.put("features", retainedFeatures);
        customLocationStore.writeCustomLocations(customLocations);
    }

    private static String getFeatureId(Object feature) {
        if (feature instanceof Map<?, ?> featureMap && featureMap.get("id") instanceof String featureId) {
            return featureId;
        }
        return null;
    }
}