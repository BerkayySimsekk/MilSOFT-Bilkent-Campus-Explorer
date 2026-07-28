package com.example.bilkentcampusexplorer.model;

import java.util.List;

public record TerrainPathRequest(
        Double startLongitude,
        Double startLatitude,
        Double endLongitude,
        Double endLatitude,
        Double maximumSlopeDegrees,
        List<List<List<Double>>> avoidanceBarriers) {

    public TerrainPathRequest {
        avoidanceBarriers = avoidanceBarriers == null ? List.of() : avoidanceBarriers;
    }

    public TerrainPathRequest(
            Double startLongitude,
            Double startLatitude,
            Double endLongitude,
            Double endLatitude) {
        this(startLongitude, startLatitude, endLongitude, endLatitude, null, List.of());
    }

    public TerrainPathRequest(
            Double startLongitude,
            Double startLatitude,
            Double endLongitude,
            Double endLatitude,
            List<List<List<Double>>> avoidanceBarriers) {
        this(startLongitude, startLatitude, endLongitude, endLatitude, null, avoidanceBarriers);
    }

    public TerrainPathRequest(
            Double startLongitude,
            Double startLatitude,
            Double endLongitude,
            Double endLatitude,
            Double maximumSlopeDegrees) {
        this(startLongitude, startLatitude, endLongitude, endLatitude, maximumSlopeDegrees, List.of());
    }
}