package com.example.bilkentcampusexplorer.model;

import java.util.List;

public record TerrainPathResponse(String type, Geometry geometry, Properties properties) {

    public record Geometry(String type, List<List<Double>> coordinates) {
    }

    public record Properties(
            double distance3DMetres,
            int segmentCount,
            double startElevationMetres,
            double endElevationMetres,
            List<Double> snappedStart,
            List<Double> snappedEnd) {
    }
}