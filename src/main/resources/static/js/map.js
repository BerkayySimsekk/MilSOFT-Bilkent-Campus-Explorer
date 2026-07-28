import GeoJSON from "https://cdn.jsdelivr.net/npm/ol@10.6.1/format/GeoJSON.js";
import { createEmpty, extend, getCenter } from "https://cdn.jsdelivr.net/npm/ol@10.6.1/extent.js";
import Draw from "https://cdn.jsdelivr.net/npm/ol@10.6.1/interaction/Draw.js";
import { toLonLat } from "https://cdn.jsdelivr.net/npm/ol@10.6.1/proj.js";
import booleanIntersects from "https://cdn.jsdelivr.net/npm/@turf/boolean-intersects@7.2.0/+esm";
import {
  deleteCustomFeature,
  loadCampusBoundary,
  loadLocationFeatures,
  removeAllCustomFeatures,
  saveCustomFeature
} from "./map-data.js";
import { createDrawingController } from "./map-drawing.js";
import { configureElevationLayer, createElevationLayer } from "./map-elevation.js";
import { createMapLayers, minLocationZoom } from "./map-layers.js";
import {
  createTerrainAvoidanceController,
  createTerrainAvoidanceLayer
} from "./map-terrain-avoidance.js";
import { createTerrainPathController, createTerrainPathLayer } from "./map-terrain-path.js";
import { createCampusMap } from "./map-view.js";

const searchInput = document.querySelector("#search-input");
const categoryVisibilityInputs = document.querySelectorAll('input[name="category-visibility"]');
const categoryExportButtons = document.querySelectorAll("[data-export-category]");
const clearAllCustomFeaturesButton = document.querySelector("#clear-all-custom-features-button");
const areaFilterButton = document.querySelector("#area-filter-button");
const featureTypeSelect = document.querySelector("#feature-type-select");
const resultCount = document.querySelector("#result-count");
const locationList = document.querySelector("#location-list");
const loadingMessage = document.querySelector("#loading-message");
const errorMessage = document.querySelector("#error-message");
const popupElement = document.querySelector("#popup");
const popupContent = document.querySelector("#popup-content");
const popupCloseButton = document.querySelector("#popup-close");
const mapModeButton = document.querySelector("#map-mode-button");
const locationFilterSidebar = document.querySelector("#location-filter-sidebar");
const locationResultsSidebar = document.querySelector("#location-results-sidebar");
const elevationLegend = document.querySelector("#elevation-legend");
const elevationLegendScale = document.querySelector("#elevation-legend-scale");
const elevationLegendStatus = document.querySelector("#elevation-legend-status");
const elevationLegendValues = document.querySelectorAll("[data-elevation-ratio]");
const terrainPathButton = document.querySelector("#terrain-path-button");
const terrainPathStatus = document.querySelector("#terrain-path-status");
const terrainAvoidanceDrawButton = document.querySelector("#terrain-avoidance-draw-button");
const terrainAvoidanceClearButton = document.querySelector("#terrain-avoidance-clear-button");
const terrainAvoidanceStatus = document.querySelector("#terrain-avoidance-status");
const terrainMaximumSlopeInput = document.querySelector("#terrain-maximum-slope-input");
const appContent = document.querySelector(".app-content");
const campusBoundaryUrl = "/data/campus-boundary.geojson";
const locationMapMode = "location";
const elevationMapMode = "elevation";

const allFeatures = [];
let visibleFeatures = [];
let selectedFeature = null;
let selectedClusterFeatures = null;
let map;
let popupOverlay;
let drawingController;
let terrainPathController;
let terrainAvoidanceController;
let campusBoundaryGeometry = null;
let selectedAreaGeometry = null;
let areaDrawInteraction = null;
let areaSelectionActive = false;
let lastLocationZoomVisibility = null;
let locationLoadRequestId = 0;
let elevationLookupRequestId = 0;
let currentMapMode = locationMapMode;
let elevationDatasetAvailable = false;

const elevationLayer = createElevationLayer();
const { source: terrainPathSource, layer: terrainPathLayer } = createTerrainPathLayer();
const { source: terrainAvoidanceSource, layer: terrainAvoidanceLayer } = createTerrainAvoidanceLayer();

const {
  locationCategories,
  locationLayerGroup,
  vectorSources,
  vectorLayers,
  pointSource,
  clusterLayer,
  areaFilterSource,
  areaFilterLayer,
  areaFilterStyle,
  drawingPreviewSource,
  drawingPreviewLayer,
  drawingPreviewLineStyle
} = createMapLayers(() => selectedFeature);

// Creates the OpenLayers map, category point layers, and map click behavior.
function initializeMap(boundaryGeometry) {
  ({ map, popupOverlay } = createCampusMap({
    boundaryGeometry,
    popupElement,
    elevationLayer,
    terrainAvoidanceLayer,
    terrainPathLayer,
    locationLayerGroup
  }));

  drawingController = createDrawingController({
    map,
    campusBoundaryGeometry: boundaryGeometry,
    featureTypeSelect,
    drawingPreviewSource,
    drawingPreviewLayer,
    drawingPreviewLineStyle,
    areaFilterLayer,
    isAreaSelectionActive: () => areaSelectionActive,
    isLocationMode: () => currentMapMode === locationMapMode,
    onDrawingStart: closePopup,
    onFeatureSelected: selectFeature,
    onCustomFeatureRequested: showCustomFeatureForm
  });
  drawingController.initialize();

  terrainAvoidanceController = createTerrainAvoidanceController({
    map,
    layer: terrainAvoidanceLayer,
    source: terrainAvoidanceSource,
    campusBoundaryGeometry: boundaryGeometry,
    drawButton: terrainAvoidanceDrawButton,
    clearButton: terrainAvoidanceClearButton,
    statusElement: terrainAvoidanceStatus,
    onDrawingStart: () => {
      terrainPathController?.reset();
      closePopup();
    },
    onBarriersChanged: () => terrainPathController?.reset()
  });

  terrainPathController = createTerrainPathController({
    map,
    layer: terrainPathLayer,
    source: terrainPathSource,
    campusBoundaryGeometry: boundaryGeometry,
    actionButton: terrainPathButton,
    statusElement: terrainPathStatus,
    elevationAvailable: elevationDatasetAvailable,
    getAvoidanceBarriers: terrainAvoidanceController.getAvoidanceBarriers,
    getMaximumSlopeDegrees,
    onSelectionStart: () => {
      terrainAvoidanceController.stopDrawing();
      closePopup();
    },
    onResultSelected: showTerrainPathPopup
  });

  terrainMaximumSlopeInput.addEventListener("input", () => terrainPathController.reset());

  map.on("singleclick", (event) => {
    if (currentMapMode === elevationMapMode) {
      if (terrainAvoidanceController.isDrawing()) {
        return;
      }
      const handledByTerrainPath = terrainPathController.handleMapClick(event.coordinate, event.pixel);
      if (!handledByTerrainPath) {
        showElevationInformation(event.coordinate);
      }
    }
  });
}

function getMaximumSlopeDegrees() {
  const inputValue = terrainMaximumSlopeInput.value.trim();
  if (inputValue === "") {
    return null;
  }

  const maximumSlopeDegrees = Number(inputValue);
  if (!Number.isFinite(maximumSlopeDegrees)
      || maximumSlopeDegrees < 0
      || maximumSlopeDegrees > 90) {
    terrainPathStatus.textContent =
      "Enter a maximum slope from 0° to 90°, or leave it blank for no limit.";
    terrainMaximumSlopeInput.focus();
    return undefined;
  }
  return maximumSlopeDegrees;
}

function setMapMode(mapMode) {
  if (!map || mapMode === currentMapMode) {
    return;
  }

  const isElevationMode = mapMode === elevationMapMode;
  currentMapMode = mapMode;
  elevationLookupRequestId += 1;

  if (isElevationMode && areaSelectionActive) {
    cancelAreaSelection();
  }
  drawingController.cancelDrawing();
  drawingController.updateFreehandDrawing();
  popupOverlay.setPosition(undefined);

  locationLayerGroup.setVisible(!isElevationMode);
  elevationLayer.setVisible(isElevationMode);
  terrainAvoidanceController.setElevationMode(isElevationMode);
  terrainPathController.setElevationMode(isElevationMode);
  locationFilterSidebar.hidden = isElevationMode;
  locationResultsSidebar.hidden = isElevationMode;
  appContent.classList.toggle("is-elevation-mode", isElevationMode);
  elevationLegend.hidden = !isElevationMode;
  mapModeButton.textContent = isElevationMode ? "Show Location Map" : "Show Elevation Map";
  mapModeButton.setAttribute("aria-pressed", String(isElevationMode));
  map.getTargetElement().setAttribute("aria-label", isElevationMode
    ? "OpenStreetMap campus elevation map"
    : "OpenStreetMap campus location map");

  if (!isElevationMode && selectedFeature) {
    showPopup(selectedFeature);
  }

  window.requestAnimationFrame(() => map.updateSize());
}

async function showElevationInformation(coordinate) {
  const requestId = ++elevationLookupRequestId;
  const [longitude, latitude] = toLonLat(coordinate, map.getView().getProjection());
  renderElevationPopup(longitude, latitude, "Loading elevation…");
  popupOverlay.setPosition(coordinate);

  try {
    const query = new URLSearchParams({
      longitude: String(longitude),
      latitude: String(latitude)
    });
    const response = await fetch(`/api/elevation?${query}`);
    const responseBody = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(responseBody.message || "Elevation is unavailable at this coordinate.");
    }
    if (requestId !== elevationLookupRequestId || currentMapMode !== elevationMapMode) {
      return;
    }

    renderElevationPopup(
      responseBody.longitude,
      responseBody.latitude,
      `${formatElevation(responseBody.elevation)} ${responseBody.unit}`
    );
  } catch (error) {
    if (requestId !== elevationLookupRequestId || currentMapMode !== elevationMapMode) {
      return;
    }
    renderElevationPopup(
      longitude,
      latitude,
      error instanceof Error ? error.message : "Elevation is unavailable at this coordinate.",
      true
    );
  }
}

function renderElevationPopup(longitude, latitude, elevationText, isError = false) {
  popupElement.setAttribute("aria-label", "Elevation details");
  popupContent.replaceChildren();

  const heading = document.createElement("h3");
  heading.textContent = "Terrain elevation";
  const details = document.createElement("dl");
  details.className = "elevation-popup-details";
  details.append(
    createElevationDetail("Longitude", Number(longitude).toFixed(6)),
    createElevationDetail("Latitude", Number(latitude).toFixed(6)),
    createElevationDetail("Elevation", elevationText, isError)
  );
  popupContent.append(heading, details);
}

function createElevationDetail(label, value, isError = false) {
  const row = document.createElement("div");
  const term = document.createElement("dt");
  const description = document.createElement("dd");
  term.textContent = label;
  description.textContent = value;
  description.classList.toggle("elevation-value-error", isError);
  row.append(term, description);
  return row;
}

function showTerrainPathPopup(geoJsonFeature, coordinate) {
  elevationLookupRequestId += 1;
  const properties = geoJsonFeature.properties;
  popupElement.setAttribute("aria-label", "Terrain path details");
  popupContent.replaceChildren();

  const heading = document.createElement("h3");
  heading.textContent = "Shortest terrain path";
  const details = document.createElement("dl");
  details.className = "elevation-popup-details";
  details.append(
    createElevationDetail("Distance", `${Number(properties.distance3DMetres).toFixed(1)} m`),
    createElevationDetail("Segments", String(properties.segmentCount)),
    createElevationDetail("Start elevation", `${formatElevation(properties.startElevationMetres)} m`),
    createElevationDetail("End elevation", `${formatElevation(properties.endElevationMetres)} m`)
  );
  const downloadLink = createGeoJsonObjectDownloadLink(
    geoJsonFeature,
    "shortest-terrain-path.geojson"
  );

  popupContent.append(heading, details, downloadLink);
  popupOverlay.setPosition(coordinate);
}

function updateElevationLegend(configuration) {
  if (!configuration.available) {
    elevationLegendScale.hidden = true;
    elevationLegendStatus.textContent = configuration.message;
    return;
  }

  elevationLegendScale.hidden = false;
  elevationLegendStatus.textContent = "Click the terrain to inspect its elevation.";
  const elevationRange = configuration.maximumElevation - configuration.minimumElevation;
  elevationLegendValues.forEach((valueElement) => {
    const ratio = Number(valueElement.dataset.elevationRatio);
    const elevation = configuration.minimumElevation + elevationRange * ratio;
    valueElement.textContent = `${formatElevation(elevation)} m`;
  });
}

function formatElevation(elevation) {
  const numericElevation = Number(elevation);
  return Number.isInteger(numericElevation) ? numericElevation.toFixed(0) : numericElevation.toFixed(1);
}

function startAreaSelection() {
  if (!map || !drawingController || !campusBoundaryGeometry || areaSelectionActive) {
    return;
  }

  areaSelectionActive = true;
  closePopup();
  areaFilterSource.clear();
  areaDrawInteraction = new Draw({
    source: areaFilterSource,
    type: "Polygon",
    style: areaFilterStyle,
    stopClick: true,
    freehandCondition: () => false,
    condition: (event) => campusBoundaryGeometry.intersectsCoordinate(event.coordinate),
    finishCondition: (event) => campusBoundaryGeometry.intersectsCoordinate(event.coordinate)
  });
  areaDrawInteraction.on("drawend", (event) => {
    selectedAreaGeometry = event.feature.getGeometry().clone();
    finishAreaSelection();
  });
  map.addInteraction(areaDrawInteraction);
  updateAreaFilterButton("Cancel area selection", true);
}

function finishAreaSelection() {
  const completedInteraction = areaDrawInteraction;
  areaDrawInteraction = null;
  areaSelectionActive = false;
  map.removeInteraction(completedInteraction);
  updateAreaFilterButton("Clear area filter", true);
  drawingController.updateFreehandDrawing();
  applyFilters();
}

function cancelAreaSelection() {
  if (!areaSelectionActive) {
    return;
  }

  const cancelledInteraction = areaDrawInteraction;
  areaDrawInteraction = null;
  areaSelectionActive = false;
  cancelledInteraction.abortDrawing();
  map.removeInteraction(cancelledInteraction);
  areaFilterSource.clear();
  selectedAreaGeometry = null;
  updateAreaFilterButton("Select area to filter", false);
  drawingController.updateFreehandDrawing();
}

function clearAreaFilter() {
  areaFilterSource.clear();
  selectedAreaGeometry = null;
  updateAreaFilterButton("Select area to filter", false);
  applyFilters();
}

function updateAreaFilterButton(text, isPressed) {
  areaFilterButton.textContent = text;
  areaFilterButton.setAttribute("aria-pressed", String(isPressed));
}

// Requests the locations available at the current zoom and turns the GeoJSON data into map features.
async function loadLocations({ fitMap = false } = {}) {
  const requestId = ++locationLoadRequestId;
  const zoom = map.getView().getZoom() ?? minLocationZoom;
  setLoading(true);
  clearError();

  try {
    const features = await loadLocationFeatures(map.getView().getProjection(), zoom);
    if (requestId !== locationLoadRequestId) {
      return;
    }

    allFeatures.splice(0, allFeatures.length, ...features);
    applyFilters({ fitMap });
  } catch (error) {
    if (requestId !== locationLoadRequestId) {
      return;
    }

    console.error("Unable to load campus locations:", error);
    allFeatures.splice(0, allFeatures.length);
    locationCategories.forEach((category) => vectorSources[category].clear());
    pointSource.clear();
    visibleFeatures = [];
    renderLocationList();
    updateResultCount();
    showError(error instanceof Error ? error.message : "An unexpected error occurred while loading locations.");
  } finally {
    if (requestId === locationLoadRequestId) {
      setLoading(false);
    }
  }
}

// Applies the current search text and category selection, then redraws the visible points and list.
function applyFilters({ fitMap = false } = {}) {
  const searchText = normalizeText(searchInput.value);
  const visibleCategories = new Set(
    [...categoryVisibilityInputs]
      .filter((input) => input.checked)
      .map((input) => input.value)
  );

  visibleFeatures = isLocationZoomVisible()
    ? allFeatures.filter((feature) => {
        const name = normalizeText(feature.get("name"));
        const category = normalizeText(feature.get("category"));
        const description = normalizeText(feature.get("description"));
        const matchesSearch = !searchText || [name, category, description].some((value) => value.includes(searchText));
        const isCategoryVisible = visibleCategories.has(feature.get("category"));
        const matchesArea = !selectedAreaGeometry || isFeatureInsideSelectedArea(feature, selectedAreaGeometry);
        return matchesArea && matchesSearch && isCategoryVisible;
      })
    : [];

  // Each category keeps its own source so it can be independently hidden or shown.
  locationCategories.forEach((category) => {
    vectorSources[category].clear();
    vectorLayers[category].setVisible(visibleCategories.has(category));
  });
  pointSource.clear();
  visibleFeatures.forEach((feature) => {
    if (feature.getGeometry()?.getType() === "Point") {
      pointSource.addFeature(feature);
      return;
    }

    const source = vectorSources[feature.get("category")];
    source?.addFeature(feature);
  });

  if (selectedClusterFeatures) {
    closePopup();
  }
  if (selectedFeature && !visibleFeatures.includes(selectedFeature)) {
    closePopup();
  }

  renderLocationList();
  updateResultCount();

  if (fitMap) {
    fitMapToFeatures();
  }
}

function isFeatureInsideSelectedArea(feature, areaGeometry) {
  const geometry = feature.getGeometry();
  if (!geometry) {
    return false;
  }

  const geoJsonFormat = new GeoJSON();
  const featureGeometry = geoJsonFormat.writeGeometryObject(geometry);
  const selectedArea = geoJsonFormat.writeGeometryObject(areaGeometry);
  return booleanIntersects(featureGeometry, selectedArea);
}

function isLocationZoomVisible() {
  return (map?.getView().getZoom() ?? minLocationZoom) > minLocationZoom;
}

function reloadLocationsWhenZoomVisibilityChanges() {
  const isVisibleAtCurrentZoom = isLocationZoomVisible();
  if (isVisibleAtCurrentZoom === lastLocationZoomVisibility) {
    return;
  }

  lastLocationZoomVisibility = isVisibleAtCurrentZoom;
  loadLocations();
}

// Builds the list of visible location cards shown in the sidebar.
function renderLocationList() {
  locationList.replaceChildren();

  if (visibleFeatures.length === 0) {
    const emptyMessage = document.createElement("p");
    emptyMessage.className = "empty-results";
    emptyMessage.textContent = isLocationZoomVisible()
      ? "No locations match the current search and category filters."
      : "Zoom in to view campus locations.";
    locationList.append(emptyMessage);
    return;
  }

  visibleFeatures.forEach((feature) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "location-card";
    if (feature === selectedFeature) {
      card.classList.add("is-selected");
    }

    const name = document.createElement("h3");
    name.textContent = feature.get("name");
    const category = document.createElement("span");
    category.className = "location-category";
    category.textContent = feature.get("category");
    const description = document.createElement("p");
    description.textContent = feature.get("description");

    card.append(name, category, description);
    card.addEventListener("click", () => selectFeature(feature));
    locationList.append(card);
  });
}

// Marks one feature as selected, shows its popup, and optionally moves the map to it.
function selectFeature(feature, { animate = true } = {}) {
  const clusteredFeatures = feature.get("features");
  if (Array.isArray(clusteredFeatures) && clusteredFeatures.length > 1) {
    showClusterPopup(feature, clusteredFeatures);
    return;
  }
  if (Array.isArray(clusteredFeatures) && clusteredFeatures.length === 1) {
    [feature] = clusteredFeatures;
  }

  selectedClusterFeatures = null;
  selectedFeature = feature;
  refreshFeatureStyles();
  renderLocationList();
  showPopup(feature);

  const coordinates = getFeatureAnchorCoordinate(feature);
  if (animate) {
    map.getView().animate({ center: coordinates, zoom: 17, duration: 450 });
  }
}

// Fills the popup with the selected location's details and its GeoJSON download link.
function showPopup(feature) {
  drawingController.stopFreehandDrawing();
  drawingController.clearFreehandDraft();
  popupElement.setAttribute("aria-label", "Location details");
  popupContent.replaceChildren();
  popupContent.append(createPopupFeatureDetails(feature));
  popupOverlay.setPosition(getFeatureAnchorCoordinate(feature));
}

function showClusterPopup(clusterFeature, features) {
  drawingController.stopFreehandDrawing();
  drawingController.clearFreehandDraft();
  selectedFeature = null;
  selectedClusterFeatures = features;
  refreshFeatureStyles();
  renderLocationList();
  popupElement.setAttribute("aria-label", `${features.length} clustered locations`);
  popupContent.replaceChildren();

  const heading = document.createElement("h3");
  heading.className = "cluster-popup-heading";
  heading.textContent = `${features.length} locations`;
  const featureList = document.createElement("div");
  featureList.className = "cluster-popup-list";
  features.forEach((feature) => featureList.append(createPopupFeatureDetails(feature)));

  popupContent.append(heading, featureList);
  popupOverlay.setPosition(clusterFeature.getGeometry().getCoordinates());
}

function createPopupFeatureDetails(feature) {
  const details = document.createElement("section");
  details.className = "popup-feature-details";

  const name = document.createElement("h3");
  name.textContent = feature.get("name");
  const category = document.createElement("p");
  category.className = "popup-category";
  category.textContent = feature.get("category");
  const description = document.createElement("p");
  description.className = "popup-description";
  description.textContent = feature.get("description");
  const downloadButton = createGeoJsonDownloadLink(feature);

  details.append(name, category, description, downloadButton);
  if (feature.get("category") === "Custom") {
    details.append(createCustomFeatureRemoveButton(feature));
  }
  return details;
}

function getFeatureAnchorCoordinate(feature) {
  const geometry = feature.getGeometry();
  if (geometry.getType() === "LineString") {
    return geometry.getLastCoordinate();
  }
  if (geometry.getType() === "Polygon") {
    return getCenter(geometry.getExtent());
  }

  return geometry.getCoordinates();
}

// Creates a browser download link containing one map feature in standard GeoJSON format.
function createGeoJsonDownloadLink(feature) {
  const geojsonFeature = new GeoJSON().writeFeatureObject(feature, {
    dataProjection: "EPSG:4326",
    featureProjection: map.getView().getProjection()
  });
  const fileName = createGeoJsonFileName(feature.get("name"));
  return createGeoJsonObjectDownloadLink(geojsonFeature, fileName);
}

function createGeoJsonObjectDownloadLink(geoJsonObject, fileName) {
  const fileContents = JSON.stringify(geoJsonObject, null, 2);
  const downloadUrl = URL.createObjectURL(new Blob([fileContents], { type: "application/geo+json" }));
  const downloadLink = document.createElement("a");

  downloadLink.href = downloadUrl;
  downloadLink.download = fileName;
  downloadLink.className = "popup-download";
  downloadLink.textContent = "Export GeoJSON";
  downloadLink.addEventListener("click", () => {
    window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000);
  }, { once: true });
  return downloadLink;
}

// Creates the removal action that is available only for locations saved by the current user.
function createCustomFeatureRemoveButton(feature) {
  const removeButton = document.createElement("button");
  removeButton.type = "button";
  removeButton.className = "popup-remove";
  removeButton.textContent = "Remove";
  removeButton.addEventListener("click", () => removeCustomFeature(feature, removeButton));
  return removeButton;
}

// Deletes one saved custom feature and immediately removes it from the visible map and list.
async function removeCustomFeature(feature, removeButton) {
  const featureId = feature.getId();
  if (!featureId) {
    showError("Unable to remove the custom location. Please try again.");
    return;
  }

  removeButton.disabled = true;
  try {
    await deleteCustomFeature(featureId);

    const featureIndex = allFeatures.indexOf(feature);
    if (featureIndex !== -1) {
      allFeatures.splice(featureIndex, 1);
    }

    closePopup();
    clearError();
    applyFilters();
  } catch (error) {
    console.error("Unable to remove custom feature:", error);
    showError("Unable to remove the custom location. Please try again.");
  } finally {
    removeButton.disabled = false;
  }
}

// Converts a location name into a simple, safe filename ending in .geojson.
function createGeoJsonFileName(name) {
  const safeName = String(name ?? "location")
    .trim()
    .toLocaleLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return `${safeName || "location"}.geojson`;
}

// Downloads all loaded features in one category, regardless of the current search or layer visibility.
function exportCategoryGeoJson(category) {
  const categoryFeatures = allFeatures.filter((feature) => feature.get("category") === category);
  const geojsonFeatureCollection = new GeoJSON().writeFeaturesObject(categoryFeatures, {
    dataProjection: "EPSG:4326",
    featureProjection: map.getView().getProjection()
  });
  const fileContents = JSON.stringify(geojsonFeatureCollection, null, 2);
  const downloadUrl = URL.createObjectURL(new Blob([fileContents], { type: "application/geo+json" }));
  const downloadLink = document.createElement("a");

  downloadLink.href = downloadUrl;
  downloadLink.download = createGeoJsonFileName(category);
  document.body.append(downloadLink);
  downloadLink.click();
  window.setTimeout(() => {
    downloadLink.remove();
    URL.revokeObjectURL(downloadUrl);
  }, 1000);
}

// Shows the form that lets a user name and describe a new custom point, line, circle, or polygon.
function showCustomFeatureForm(coordinates, geometryType, popupCoordinate = coordinates.at(-1)) {
  drawingController.stopFreehandDrawing();
  const featureLabel = getFeatureTypeLabel(geometryType);
  selectedFeature = null;
  selectedClusterFeatures = null;
  refreshFeatureStyles();
  renderLocationList();
  popupElement.setAttribute("aria-label", `Add custom ${featureLabel}`);
  popupContent.replaceChildren();

  const heading = document.createElement("h3");
  heading.textContent = `Add custom ${featureLabel}`;

  const form = document.createElement("form");
  form.className = "custom-point-form";

  const nameLabel = document.createElement("label");
  nameLabel.htmlFor = "custom-point-name";
  nameLabel.textContent = "Name";
  const nameInput = document.createElement("input");
  nameInput.id = "custom-point-name";
  nameInput.name = "name";
  nameInput.type = "text";
  nameInput.required = true;
  nameInput.maxLength = 100;
  nameInput.autocomplete = "off";

  const descriptionLabel = document.createElement("label");
  descriptionLabel.htmlFor = "custom-point-description";
  descriptionLabel.textContent = "Description";
  const descriptionInput = document.createElement("textarea");
  descriptionInput.id = "custom-point-description";
  descriptionInput.name = "description";
  descriptionInput.rows = 3;
  descriptionInput.maxLength = 500;

  const actions = document.createElement("div");
  actions.className = "custom-point-actions";
  const addButton = document.createElement("button");
  addButton.type = "submit";
  addButton.className = "custom-point-add";
  addButton.textContent = `Add ${featureLabel}`;
  const cancelButton = document.createElement("button");
  cancelButton.type = "button";
  cancelButton.className = "custom-point-cancel";
  cancelButton.textContent = "Cancel";

  actions.append(addButton, cancelButton);
  form.append(nameLabel, nameInput, descriptionLabel, descriptionInput, actions);
  popupContent.append(heading, form);
  popupOverlay.setPosition(popupCoordinate);
  nameInput.focus();

  cancelButton.addEventListener("click", closePopup);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();

    const name = nameInput.value.trim();
    if (!name) {
      nameInput.focus();
      return;
    }

    addButton.disabled = true;
    cancelButton.disabled = true;
    try {
      const customFeature = await saveCustomFeature({
        name,
        description: descriptionInput.value.trim(),
        geometryType,
        coordinates,
        projection: map.getView().getProjection()
      });
      allFeatures.push(customFeature);
      drawingController.cancelDrawing();
      if (geometryType === "Freehand") {
        featureTypeSelect.value = "Point";
      }
      drawingController.updateFreehandDrawing();
      clearError();
      applyFilters();
      closePopup();
    } catch (error) {
      console.error(`Unable to save custom ${featureLabel}:`, error);
      showError(`Unable to save the custom ${featureLabel}. Please try again.`);
    } finally {
      addButton.disabled = false;
      cancelButton.disabled = false;
    }
  });
}

// Hides the popup and removes the current selection from the map and sidebar.
function closePopup() {
  if (currentMapMode === elevationMapMode) {
    elevationLookupRequestId += 1;
    popupOverlay.setPosition(undefined);
    return;
  }

  drawingController.cancelDrawing();
  selectedFeature = null;
  selectedClusterFeatures = null;
  popupOverlay.setPosition(undefined);
  refreshFeatureStyles();
  renderLocationList();
  drawingController.updateFreehandDrawing();
}

// Zooms and pans the map so all currently visible points fit on screen.
function fitMapToFeatures() {
  if (visibleFeatures.length === 0) {
    return;
  }

  const extent = createEmpty();
  visibleFeatures.forEach((feature) => extend(extent, feature.getGeometry().getExtent()));

  // Fit the Web Mercator view to the feature extent, with room for map controls and points.
  map.getView().fit(extent, {
    padding: [70, 70, 70, 70],
    maxZoom: 16,
    duration: 400
  });
}

// Requests a redraw from every location layer after the selected feature changes.
function refreshFeatureStyles() {
  Object.values(vectorLayers).forEach((layer) => layer.changed());
  clusterLayer.changed();
}

// Changes text to lowercase, trimmed text so searching is not affected by capitalization or extra spaces.
function normalizeText(value) {
  return String(value ?? "").trim().toLocaleLowerCase();
}

// Updates the sidebar text that tells the user how many locations are visible.
function updateResultCount() {
  const count = visibleFeatures.length;
  resultCount.textContent = `${count} ${count === 1 ? "location" : "locations"}`;
}

// Shows or hides the loading message while location data is being requested.
function setLoading(isLoading) {
  loadingMessage.hidden = !isLoading;
}

// Displays an error message in the sidebar when an operation cannot be completed.
function showError(message) {
  errorMessage.textContent = message;
  errorMessage.hidden = false;
}

// Removes any error message that was previously shown.
function clearError() {
  errorMessage.textContent = "";
  errorMessage.hidden = true;
}

// Removes every saved custom feature and immediately updates the map and list.
async function clearAllCustomFeatures() {
  clearAllCustomFeaturesButton.disabled = true;
  try {
    await removeAllCustomFeatures();

    const selectedCustomFeature = selectedFeature?.get("category") === "Custom";
    for (let index = allFeatures.length - 1; index >= 0; index -= 1) {
      if (allFeatures[index].get("category") === "Custom") {
        allFeatures.splice(index, 1);
      }
    }

    if (selectedCustomFeature) {
      selectedFeature = null;
      popupOverlay.setPosition(undefined);
    }

    clearError();
    applyFilters();
  } catch (error) {
    console.error("Unable to clear all custom locations:", error);
    showError("Unable to clear all custom locations. Please try again.");
  } finally {
    clearAllCustomFeaturesButton.disabled = false;
  }
}

function getFeatureTypeLabel(geometryType) {
  if (geometryType === "LineString") {
    return "line";
  }
  if (geometryType === "Freehand") {
    return "freehand drawing";
  }
  if (geometryType === "Circle") {
    return "circle";
  }
  if (geometryType === "Polygon") {
    return "polygon";
  }
  return "point";
}

searchInput.addEventListener("input", () => applyFilters());
categoryVisibilityInputs.forEach((input) => input.addEventListener("change", () => applyFilters()));
categoryExportButtons.forEach((button) => {
  button.addEventListener("click", () => exportCategoryGeoJson(button.dataset.exportCategory));
});
featureTypeSelect.addEventListener("change", () => {
  drawingController.cancelDrawing();
  drawingController.updateFreehandDrawing();
});
clearAllCustomFeaturesButton.addEventListener("click", clearAllCustomFeatures);
areaFilterButton.addEventListener("click", () => {
  if (areaSelectionActive) {
    cancelAreaSelection();
  } else if (selectedAreaGeometry) {
    clearAreaFilter();
  } else {
    startAreaSelection();
  }
});
popupCloseButton.addEventListener("click", closePopup);
mapModeButton.addEventListener("click", () => {
  setMapMode(currentMapMode === locationMapMode ? elevationMapMode : locationMapMode);
});
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") {
    return;
  }
  if (terrainAvoidanceController?.stopDrawing()) {
    event.preventDefault();
  } else if (terrainPathController?.cancelSelection()) {
    event.preventDefault();
  } else if (areaSelectionActive) {
    event.preventDefault();
    cancelAreaSelection();
  }
});

async function startApplication() {
  try {
    const [boundaryGeometry, elevationConfiguration] = await Promise.all([
      loadCampusBoundary(campusBoundaryUrl),
      configureElevationLayer(elevationLayer)
    ]);
    campusBoundaryGeometry = boundaryGeometry;
    elevationDatasetAvailable = elevationConfiguration.available;
    updateElevationLegend(elevationConfiguration);
    initializeMap(campusBoundaryGeometry);
    lastLocationZoomVisibility = isLocationZoomVisible();
    map.getView().on("change:resolution", () => applyFilters());
    map.on("moveend", reloadLocationsWhenZoomVisibilityChanges);
    await loadLocations({ fitMap: true });
  } catch (error) {
    console.error("Unable to initialize the campus map:", error);
    showError("Unable to load the campus boundary. Please refresh the page and try again.");
  }
}

startApplication();
