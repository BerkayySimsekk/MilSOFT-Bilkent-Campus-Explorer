package com.example.bilkentcampusexplorer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.ArgumentCaptor;

import com.example.bilkentcampusexplorer.model.CustomLocationRequest;
import com.example.bilkentcampusexplorer.model.ElevationResponse;
import com.example.bilkentcampusexplorer.model.TerrainPathRequest;
import com.example.bilkentcampusexplorer.model.TerrainPathResponse;
import com.example.bilkentcampusexplorer.service.ElevationService;
import com.example.bilkentcampusexplorer.service.LocationService;

@WebMvcTest(LocationController.class)
class LocationControllerTest {

    private static final MediaType GEO_JSON = MediaType.parseMediaType("application/geo+json");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LocationService locationService;

    @MockitoBean
    ElevationService elevationService;

    @Test
    void returnsLocationsWithoutZoomAsGeoJson() throws Exception {
        when(locationService.getLocations(null)).thenReturn(Map.of(
                "type", "FeatureCollection",
                "features", List.of(Map.of("type", "Feature", "id", "building-one"))));

        mockMvc.perform(get("/api/locations").accept(GEO_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(GEO_JSON))
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.features[0].id").value("building-one"));

        verify(locationService).getLocations(null);
    }

    @Test
    void passesZoomToLocationService() throws Exception {
        when(locationService.getLocations(16.0)).thenReturn(Map.of(
                "type", "FeatureCollection", "features", List.of()));

        mockMvc.perform(get("/api/locations")
                        .param("zoom", "16")
                        .accept(GEO_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(GEO_JSON))
                .andExpect(jsonPath("$.features").isEmpty());

        verify(locationService).getLocations(16.0);
    }

    @Test
    void returnsElevationAndPassesCoordinatesToService() throws Exception {
        when(elevationService.getElevation(32.7481, 39.8684))
                .thenReturn(new ElevationResponse(32.7481, 39.8684, 1025.5, "metres"));

        mockMvc.perform(get("/api/elevation")
                        .param("longitude", "32.7481")
                        .param("latitude", "39.8684"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.longitude").value(32.7481))
                .andExpect(jsonPath("$.latitude").value(39.8684))
                .andExpect(jsonPath("$.elevation").value(1025.5))
                .andExpect(jsonPath("$.unit").value("metres"));

        verify(elevationService).getElevation(32.7481, 39.8684);
    }

    @Test
    void returnsUsefulJsonErrorWhenElevationLookupFails() throws Exception {
        when(elevationService.getElevation(33.0, 40.0))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No elevation data."));

        mockMvc.perform(get("/api/elevation")
                        .param("longitude", "33")
                        .param("latitude", "40"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("No elevation data."));
    }

    @Test
    void createsCustomLocationFromRequestJson() throws Exception {
        Map<String, Object> feature = Map.of(
                "type", "Feature",
                "id", "custom-created",
                "properties", Map.of("name", "Study Garden"),
                "geometry", Map.of("type", "Point", "coordinates", List.of(32.7481, 39.8684)));
        when(locationService.addCustomLocation(any(CustomLocationRequest.class))).thenReturn(feature);

        mockMvc.perform(post("/api/custom-locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(GEO_JSON)
                        .content("""
                                {
                                  "name": "Study Garden",
                                  "description": "Quiet outdoor tables",
                                  "geometryType": "Point",
                                  "coordinates": [[32.7481, 39.8684]]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(GEO_JSON))
                .andExpect(jsonPath("$.type").value("Feature"))
                .andExpect(jsonPath("$.id").value("custom-created"))
                .andExpect(jsonPath("$.geometry.coordinates[0]").value(32.7481));

        verify(locationService).addCustomLocation(new CustomLocationRequest(
                "Study Garden",
                "Quiet outdoor tables",
                "Point",
                List.of(List.of(32.7481, 39.8684))));
    }

    @Test
    void clearsAllCustomLocations() throws Exception {
        mockMvc.perform(delete("/api/custom-locations"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(locationService).clearCustomLocations();
    }

    @Test
    void deletesCustomLocationById() throws Exception {
        mockMvc.perform(delete("/api/custom-locations/custom-123"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(locationService).deleteCustomLocation("custom-123");
    }

    @Test
    void returnsNotFoundWhenCustomLocationDoesNotExist() throws Exception {
        org.mockito.Mockito.doThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Custom location was not found."))
                .when(locationService).deleteCustomLocation("custom-missing");

        mockMvc.perform(delete("/api/custom-locations/custom-missing"))
                .andExpect(status().isNotFound());

        verify(locationService).deleteCustomLocation("custom-missing");
    }

    @Test
    void returnsShortestTerrainPathAsGeoJson() throws Exception {
        TerrainPathRequest request = new TerrainPathRequest(32.7481, 39.8684, 32.7520, 39.8720);
        TerrainPathResponse response = new TerrainPathResponse(
                "Feature",
                new TerrainPathResponse.Geometry("LineString", List.of(
                        List.of(32.748194, 39.868472),
                        List.of(32.751806, 39.871806))),
                new TerrainPathResponse.Properties(
                        248.6,
                        1,
                        1025.0,
                        1041.0,
                        List.of(32.748194, 39.868472),
                        List.of(32.751806, 39.871806)));
        when(elevationService.getShortestTerrainPath(any(TerrainPathRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/elevation/shortest-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(GEO_JSON)
                        .content("""
                                {
                                  "startLongitude": 32.7481,
                                  "startLatitude": 39.8684,
                                  "endLongitude": 32.7520,
                                  "endLatitude": 39.8720
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(GEO_JSON))
                .andExpect(jsonPath("$.type").value("Feature"))
                .andExpect(jsonPath("$.geometry.type").value("LineString"))
                .andExpect(jsonPath("$.properties.distance3DMetres").value(248.6))
                .andExpect(jsonPath("$.properties.segmentCount").value(1))
                .andExpect(jsonPath("$.properties.snappedStart[0]").value(32.748194));
        verify(elevationService).getShortestTerrainPath(request);
    }

    @Test
    void returnsUsefulJsonErrorForInvalidPathRequest() throws Exception {
        when(elevationService.getShortestTerrainPath(any(TerrainPathRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coordinates are invalid."));

        mockMvc.perform(post("/api/elevation/shortest-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(GEO_JSON)
                        .content("""
                                {
                                  "startLongitude": 181,
                                  "startLatitude": 39.8684,
                                  "endLongitude": 32.7520,
                                  "endLatitude": 39.8720
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(GEO_JSON))
                .andExpect(jsonPath("$.message").value("Coordinates are invalid."));
    }

    @Test
    void acceptsAvoidanceBarriersAndPassesThemToElevationService() throws Exception {
        TerrainPathResponse response = new TerrainPathResponse(
                "Feature",
                new TerrainPathResponse.Geometry("LineString", List.of(
                        List.of(32.7481, 39.8684),
                        List.of(32.7520, 39.8720))),
                new TerrainPathResponse.Properties(
                        248.6, 1, 1025.0, 1041.0,
                        List.of(32.7481, 39.8684), List.of(32.7520, 39.8720)));
        when(elevationService.getShortestTerrainPath(any(TerrainPathRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/elevation/shortest-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(GEO_JSON)
                        .content("""
                                {
                                  "startLongitude": 32.7481,
                                  "startLatitude": 39.8684,
                                  "endLongitude": 32.7520,
                                  "endLatitude": 39.8720,
                                  "avoidanceBarriers": [
                                    [[32.7490, 39.8690], [32.7496, 39.8696]],
                                    [[32.7510, 39.8700], [32.7512, 39.8708]]
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<TerrainPathRequest> requestCaptor = ArgumentCaptor.forClass(TerrainPathRequest.class);
        verify(elevationService).getShortestTerrainPath(requestCaptor.capture());
        assertThat(requestCaptor.getValue().avoidanceBarriers()).containsExactly(
                List.of(List.of(32.7490, 39.8690), List.of(32.7496, 39.8696)),
                List.of(List.of(32.7510, 39.8700), List.of(32.7512, 39.8708)));
    }

    @Test
    void deserializesMaximumSlopeAndEmptyAvoidanceBarriers() throws Exception {
        TerrainPathResponse response = new TerrainPathResponse(
                "Feature",
                new TerrainPathResponse.Geometry("LineString", List.of(
                        List.of(32.7481, 39.8684), List.of(32.7520, 39.8720))),
                new TerrainPathResponse.Properties(
                        248.6, 1, 1025.0, 1041.0,
                        List.of(32.7481, 39.8684), List.of(32.7520, 39.8720)));
        when(elevationService.getShortestTerrainPath(any(TerrainPathRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/elevation/shortest-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(GEO_JSON)
                        .content("""
                                {
                                  "startLongitude": 32.7481,
                                  "startLatitude": 39.8684,
                                  "endLongitude": 32.7520,
                                  "endLatitude": 39.8720,
                                  "maximumSlopeDegrees": 15.5,
                                  "avoidanceBarriers": []
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<TerrainPathRequest> requestCaptor = ArgumentCaptor.forClass(TerrainPathRequest.class);
        verify(elevationService).getShortestTerrainPath(requestCaptor.capture());
        assertThat(requestCaptor.getValue().maximumSlopeDegrees()).isEqualTo(15.5);
        assertThat(requestCaptor.getValue().avoidanceBarriers()).isEmpty();
    }

    @Test
    void omittedMaximumSlopeDeserializesAsNull() throws Exception {
        when(elevationService.getShortestTerrainPath(any(TerrainPathRequest.class))).thenReturn(
                new TerrainPathResponse(
                        "Feature",
                        new TerrainPathResponse.Geometry("LineString", List.of(
                                List.of(32.7481, 39.8684), List.of(32.7520, 39.8720))),
                        new TerrainPathResponse.Properties(
                                248.6, 1, 1025.0, 1041.0,
                                List.of(32.7481, 39.8684), List.of(32.7520, 39.8720))));

        mockMvc.perform(post("/api/elevation/shortest-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(GEO_JSON)
                        .content("""
                                {
                                  "startLongitude": 32.7481,
                                  "startLatitude": 39.8684,
                                  "endLongitude": 32.7520,
                                  "endLatitude": 39.8720,
                                  "avoidanceBarriers": []
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<TerrainPathRequest> requestCaptor = ArgumentCaptor.forClass(TerrainPathRequest.class);
        verify(elevationService).getShortestTerrainPath(requestCaptor.capture());
        assertThat(requestCaptor.getValue().maximumSlopeDegrees()).isNull();
    }
}