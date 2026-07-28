import GeoJSON from "https://cdn.jsdelivr.net/npm/ol@10.6.1/format/GeoJSON.js";
import { toLonLat } from "https://cdn.jsdelivr.net/npm/ol@10.6.1/proj.js";

export async function loadCampusBoundary(campusBoundaryUrl) {
  const response = await fetch(campusBoundaryUrl);
  if (!response.ok) {
    throw new Error(`Campus boundary request failed with HTTP ${response.status}.`);
  }

  const boundaryGeoJson = await response.json();
  const [boundaryFeature] = new GeoJSON().readFeatures(boundaryGeoJson, {
    dataProjection: "EPSG:4326",
    featureProjection: "EPSG:3857"
  });
  const boundaryGeometry = boundaryFeature?.getGeometry();

  if (!boundaryGeometry || boundaryGeometry.getType() !== "Polygon") {
    throw new Error("The campus boundary must contain one Polygon feature.");
  }

  return boundaryGeometry;
}

export async function loadLocationFeatures(projection, zoom) {
  // The API returns GeoJSON coordinates as [longitude, latitude] in EPSG:4326.
  const response = await fetch(`/api/locations?zoom=${encodeURIComponent(zoom)}`);
  if (!response.ok) {
    throw new Error(`Location request failed with HTTP ${response.status}.`);
  }

  let geojson;
  try {
    geojson = await response.json();
  } catch (error) {
    throw new Error("The location service returned invalid JSON.", { cause: error });
  }

  if (geojson?.type !== "FeatureCollection" || !Array.isArray(geojson.features)) {
    throw new Error("The location service returned an invalid GeoJSON FeatureCollection.");
  }
  // OpenLayers converts geographic EPSG:4326 coordinates into the map view projection (EPSG:3857).
  const features = new GeoJSON().readFeatures(geojson, {
    dataProjection: "EPSG:4326",
    featureProjection: projection
  });

  if (geojson.features.length > 0 && features.length === 0) {
    throw new Error("The location service contained no readable locations.");
  }

  return features;
}

export async function saveCustomFeature({ name, description, geometryType, coordinates, projection }) {
  const geographicCoordinates = coordinates.map((coordinate) => toLonLat(coordinate, projection));
  const response = await fetch("/api/custom-locations", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, description, geometryType, coordinates: geographicCoordinates })
  });

  if (!response.ok) {
    throw new Error(`Custom ${geometryType} request failed with HTTP ${response.status}.`);
  }

  const geojsonFeature = await response.json();
  return new GeoJSON().readFeature(geojsonFeature, {
    dataProjection: "EPSG:4326",
    featureProjection: projection
  });
}

export async function deleteCustomFeature(featureId) {
  const response = await fetch(`/api/custom-locations/${encodeURIComponent(featureId)}`, {
    method: "DELETE"
  });
  if (!response.ok) {
    throw new Error(`Remove custom location request failed with HTTP ${response.status}.`);
  }
}

export async function removeAllCustomFeatures() {
  const response = await fetch("/api/custom-locations", {
    method: "DELETE"
  });
  if (!response.ok) {
    throw new Error(`Clear custom locations request failed with HTTP ${response.status}.`);
  }
}