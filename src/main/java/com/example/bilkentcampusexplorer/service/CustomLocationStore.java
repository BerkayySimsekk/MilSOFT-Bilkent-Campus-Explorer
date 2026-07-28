package com.example.bilkentcampusexplorer.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class CustomLocationStore {

    private static final TypeReference<Map<String, Object>> GEO_JSON_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path customLocationsPath;

    public CustomLocationStore(ObjectMapper objectMapper,
            @Value("${app.custom-locations-file:./src/main/resources/data/custom.geojson}") String customLocationsFile) {
        this.objectMapper = objectMapper;
        this.customLocationsPath = Path.of(customLocationsFile);
    }

    public Map<String, Object> readCustomLocations() {
        if (Files.notExists(customLocationsPath)) {
            return emptyFeatureCollection();
        }

        try (InputStream inputStream = Files.newInputStream(customLocationsPath)) {
            return readFeatureCollection(inputStream, "custom");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read custom GeoJSON locations file.", exception);
        }
    }

    public Map<String, Object> readFeatureCollection(InputStream inputStream, String sourceName) throws IOException {
        Map<String, Object> featureCollection = objectMapper.readValue(inputStream, GEO_JSON_TYPE);
        if (!"FeatureCollection".equals(featureCollection.get("type"))) {
            throw new IllegalStateException("The " + sourceName + " GeoJSON file is not a FeatureCollection.");
        }
        getFeatures(featureCollection);
        return featureCollection;
    }

    public List<Object> getFeatures(Map<String, Object> featureCollection) {
        Object features = featureCollection.get("features");
        if (!(features instanceof List<?> featureList)) {
            throw new IllegalStateException("The GeoJSON FeatureCollection has no features array.");
        }
        return new ArrayList<>(featureList);
    }

    public Map<String, Object> emptyFeatureCollection() {
        Map<String, Object> featureCollection = new LinkedHashMap<>();
        featureCollection.put("type", "FeatureCollection");
        featureCollection.put("features", new ArrayList<>());
        return featureCollection;
    }

    public void writeCustomLocations(Map<String, Object> customLocations) {
        Path targetPath = customLocationsPath.toAbsolutePath().normalize();
        Path parentPath = targetPath.getParent();

        try {
            Files.createDirectories(parentPath);
            Path temporaryPath = Files.createTempFile(parentPath, "custom-locations-", ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryPath.toFile(), customLocations);
                moveAtomically(temporaryPath, targetPath);
            } finally {
                Files.deleteIfExists(temporaryPath);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save custom GeoJSON locations file.", exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}