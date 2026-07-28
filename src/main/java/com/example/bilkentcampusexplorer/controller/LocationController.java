package com.example.bilkentcampusexplorer.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.bilkentcampusexplorer.model.CustomLocationRequest;
import com.example.bilkentcampusexplorer.model.TerrainPathRequest;
import com.example.bilkentcampusexplorer.service.ElevationService;
import com.example.bilkentcampusexplorer.service.LocationService;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class LocationController {

    private final LocationService locationService;
    private final ElevationService elevationService;

    public LocationController(LocationService locationService, ElevationService elevationService) {
        this.locationService = locationService;
        this.elevationService = elevationService;
    }

    @GetMapping(value = "/locations", produces = "application/geo+json")
    public Map<String, Object> getLocations(@RequestParam(required = false) Double zoom) {
        return locationService.getLocations(zoom);
    }

    @GetMapping("/elevation")
    public ResponseEntity<?> getElevation(@RequestParam Double longitude, @RequestParam Double latitude) {
        try {
            return ResponseEntity.ok(elevationService.getElevation(longitude, latitude));
        } catch (ResponseStatusException exception) {
            return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason()));
        }
    }

    @PostMapping(
            value = "/elevation/shortest-path",
            consumes = "application/json",
            produces = "application/geo+json")
    public ResponseEntity<?> getShortestTerrainPath(@RequestBody TerrainPathRequest request) {
        try {
            return ResponseEntity.ok(elevationService.getShortestTerrainPath(request));
        } catch (ResponseStatusException exception) {
            return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason()));
        }
    }

    @PostMapping(value = "/custom-locations", consumes = "application/json", produces = "application/geo+json")
    public ResponseEntity<Map<String, Object>> addCustomLocation(@RequestBody CustomLocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationService.addCustomLocation(request));
    }

    @DeleteMapping("/custom-locations")
    public ResponseEntity<Void> clearCustomLocations() {
        locationService.clearCustomLocations();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/custom-locations/{featureId}")
    public ResponseEntity<Void> deleteCustomLocation(@PathVariable String featureId) {
        locationService.deleteCustomLocation(featureId);
        return ResponseEntity.noContent().build();
    }
}