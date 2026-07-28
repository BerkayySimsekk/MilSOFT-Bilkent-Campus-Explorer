package com.example.bilkentcampusexplorer.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class GeoJsonFeatureFactory {

    public Map<String, Object> createCustomFeature(String name, String description, CustomGeometry customGeometry) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("description", description);
        properties.put("category", "Custom");
        if ("Circle".equals(customGeometry.type()) || "Freehand".equals(customGeometry.type())) {
            properties.put("customFeatureType", customGeometry.type());
        }

        Map<String, Object> geometry = new LinkedHashMap<>();
        if ("Circle".equals(customGeometry.type()) || "Polygon".equals(customGeometry.type())) {
            geometry.put("type", "Polygon");
            geometry.put("coordinates", List.of(customGeometry.coordinates()));
        } else {
            geometry.put("type", "Freehand".equals(customGeometry.type()) ? "LineString" : customGeometry.type());
            geometry.put("coordinates", "Point".equals(customGeometry.type())
                    ? customGeometry.coordinates().getFirst()
                    : customGeometry.coordinates());
        }

        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("id", "custom-" + UUID.randomUUID());
        feature.put("properties", properties);
        feature.put("geometry", geometry);
        return feature;
    }
}