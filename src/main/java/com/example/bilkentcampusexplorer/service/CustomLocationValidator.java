package com.example.bilkentcampusexplorer.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.example.bilkentcampusexplorer.model.CustomLocationRequest;

@Component
public class CustomLocationValidator {

    public String requireName(CustomLocationRequest request) {
        if (request == null || request.name() == null || request.name().trim().isEmpty()) {
            throw badRequest("A custom location name is required.");
        }

        String name = request.name().trim();
        if (name.length() > 100) {
            throw badRequest("A custom location name must be 100 characters or fewer.");
        }
        return name;
    }

    public String requireDescription(CustomLocationRequest request) {
        String description = request.description() == null ? "" : request.description().trim();
        if (description.length() > 500) {
            throw badRequest("A custom location description must be 500 characters or fewer.");
        }
        return description;
    }

    public CustomGeometry validateGeometry(CustomLocationRequest request) {
        if (request == null || !isSupportedGeometryType(request.geometryType())) {
            throw badRequest("Custom location geometry type is invalid.");
        }

        List<List<Double>> coordinates = request.coordinates();
        boolean isAreaGeometry = "Circle".equals(request.geometryType()) || "Polygon".equals(request.geometryType());
        int requiredCoordinateCount = "Point".equals(request.geometryType()) ? 1
            : "LineString".equals(request.geometryType()) || "Freehand".equals(request.geometryType()) ? 2 : 4;
        boolean hasExpectedCoordinateCount = isAreaGeometry || "Freehand".equals(request.geometryType())
            ? coordinates != null && coordinates.size() >= requiredCoordinateCount
            : coordinates != null && coordinates.size() == requiredCoordinateCount;
        if (!hasExpectedCoordinateCount) {
            throw badRequest("Custom location coordinates are invalid.");
        }

        List<List<Double>> validatedCoordinates = new ArrayList<>();
        for (List<Double> coordinate : coordinates) {
            if (coordinate == null || coordinate.size() != 2 || coordinate.get(0) == null || coordinate.get(1) == null
                    || !Double.isFinite(coordinate.get(0)) || !Double.isFinite(coordinate.get(1))
                    || coordinate.get(0) < -180 || coordinate.get(0) > 180
                    || coordinate.get(1) < -90 || coordinate.get(1) > 90) {
                throw badRequest("Custom location coordinates are invalid.");
            }
            validatedCoordinates.add(List.of(coordinate.get(0), coordinate.get(1)));
        }

        if (isAreaGeometry
                && !validatedCoordinates.getFirst().equals(validatedCoordinates.getLast())) {
            throw badRequest("Custom area coordinates must form a closed ring.");
        }

        return new CustomGeometry(request.geometryType(), validatedCoordinates);
    }

    private static boolean isSupportedGeometryType(String geometryType) {
        return "Point".equals(geometryType) || "LineString".equals(geometryType) || "Circle".equals(geometryType)
            || "Polygon".equals(geometryType) || "Freehand".equals(geometryType);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

record CustomGeometry(String type, List<List<Double>> coordinates) {
}