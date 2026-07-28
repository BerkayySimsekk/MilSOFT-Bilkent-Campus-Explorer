package com.example.bilkentcampusexplorer.model;

import java.util.List;

public record CustomLocationRequest(String name, String description, String geometryType,
        List<List<Double>> coordinates) {
}