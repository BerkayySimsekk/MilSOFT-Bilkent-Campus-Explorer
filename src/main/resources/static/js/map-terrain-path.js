import Feature from "https://cdn.jsdelivr.net/npm/ol@10.6.1/Feature.js";
import GeoJSON from "https://cdn.jsdelivr.net/npm/ol@10.6.1/format/GeoJSON.js";
import Point from "https://cdn.jsdelivr.net/npm/ol@10.6.1/geom/Point.js";
import VectorLayer from "https://cdn.jsdelivr.net/npm/ol@10.6.1/layer/Vector.js";
import { toLonLat, transform } from "https://cdn.jsdelivr.net/npm/ol@10.6.1/proj.js";
import VectorSource from "https://cdn.jsdelivr.net/npm/ol@10.6.1/source/Vector.js";
import CircleStyle from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Circle.js";
import Fill from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Fill.js";
import Stroke from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Stroke.js";
import Style from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Style.js";
import Text from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Text.js";

const idleState = "idle";
const selectingStartState = "selecting-start";
const selectingEndState = "selecting-end";
const calculatingState = "calculating";
const resultState = "result";

const routeStyles = [
  new Style({ stroke: new Stroke({ color: "rgba(255, 255, 255, 0.96)", width: 6 }) }),
  new Style({ stroke: new Stroke({ color: "#b42318", width: 3 }) })
];
const markerStyles = {
  start: createMarkerStyle("S", "#087f5b"),
  end: createMarkerStyle("E", "#b42318")
};

export function createTerrainPathLayer() {
  const source = new VectorSource({ wrapX: false });
  const layer = new VectorLayer({
    source,
    visible: false,
    zIndex: 50,
    style: (feature) => {
      const role = feature.get("terrainPathRole");
      return role === "route" ? routeStyles : markerStyles[role];
    }
  });
  return { source, layer };
}

export function createTerrainPathController({
  map,
  layer,
  source,
  campusBoundaryGeometry,
  actionButton,
  statusElement,
  elevationAvailable,
  getAvoidanceBarriers,
  getMaximumSlopeDegrees,
  onSelectionStart,
  onResultSelected
}) {
  let state = idleState;
  let isElevationMode = false;
  let startCoordinate = null;
  let resultGeoJson = null;
  let abortController = null;
  let requestId = 0;
  let selectedMaximumSlopeDegrees = null;

  actionButton.addEventListener("click", () => {
    if (state === idleState) {
      activate();
    } else {
      reset();
    }
  });
  map.on("pointermove", (event) => {
    const isHoveringRoute = !event.dragging
      && state === resultState
      && Boolean(getRouteAtPixel(event.pixel));
    map.getTargetElement().classList.toggle("is-terrain-path-route-hover", isHoveringRoute);
  });
  updateControls();

  function activate() {
    if (!elevationAvailable || !isElevationMode || state !== idleState) {
      return;
    }

    const maximumSlopeDegrees = getMaximumSlopeDegrees();
    if (maximumSlopeDegrees === undefined) {
      return;
    }

    onSelectionStart();
    source.clear();
    selectedMaximumSlopeDegrees = maximumSlopeDegrees;
    state = selectingStartState;
    statusElement.textContent = "Select the start point.";
    updateControls();
  }

  function handleMapClick(coordinate, pixel) {
    if (state === idleState) {
      return false;
    }
    if (state === resultState) {
      const clickedRoute = getRouteAtPixel(pixel);
      if (!clickedRoute) {
        return false;
      }

      onResultSelected(resultGeoJson, coordinate);
      return true;
    }
    if (state === calculatingState) {
      return true;
    }
    if (!campusBoundaryGeometry.intersectsCoordinate(coordinate)) {
      statusElement.textContent = state === selectingStartState
        ? "Select a start point inside the campus boundary."
        : "Select a destination inside the campus boundary.";
      return true;
    }

    if (state === selectingStartState) {
      startCoordinate = toLonLat(coordinate, map.getView().getProjection());
      source.clear();
      source.addFeature(createMarkerFeature(coordinate, "start"));
      state = selectingEndState;
      statusElement.textContent = "Select the destination point.";
      updateControls();
      return true;
    }

    const endCoordinate = toLonLat(coordinate, map.getView().getProjection());
    source.addFeature(createMarkerFeature(coordinate, "end"));
    calculatePath(startCoordinate, endCoordinate);
    return true;
  }

  function getRouteAtPixel(pixel) {
    return map.forEachFeatureAtPixel(
      pixel,
      (feature) => feature.get("terrainPathRole") === "route" ? feature : undefined,
      {
        hitTolerance: 5,
        layerFilter: (candidateLayer) => candidateLayer === layer
      }
    );
  }

  async function calculatePath(selectedStart, selectedEnd) {
    state = calculatingState;
    statusElement.textContent = "Calculating shortest terrain path...";
    updateControls();
    abortController = new AbortController();
    const currentRequestId = ++requestId;

    try {
      const response = await fetch("/api/elevation/shortest-path", {
        method: "POST",
        headers: {
          "Accept": "application/geo+json",
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          startLongitude: selectedStart[0],
          startLatitude: selectedStart[1],
          endLongitude: selectedEnd[0],
          endLatitude: selectedEnd[1],
          maximumSlopeDegrees: selectedMaximumSlopeDegrees,
          avoidanceBarriers: getAvoidanceBarriers()
        }),
        signal: abortController.signal
      });
      const responseBody = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(responseBody.message || "The terrain path could not be calculated.");
      }
      if (currentRequestId !== requestId || !isElevationMode) {
        return;
      }

      renderResult(responseBody);
      state = resultState;
      const slopeStatus = selectedMaximumSlopeDegrees === null
        ? ""
        : ` · maximum slope ${selectedMaximumSlopeDegrees}°`;
      statusElement.textContent = `Shortest terrain distance: ${Number(responseBody.properties.distance3DMetres).toFixed(1)} m · ${responseBody.properties.segmentCount} segments${slopeStatus}`;
      updateControls();
    } catch (error) {
      if (currentRequestId !== requestId || error?.name === "AbortError") {
        return;
      }

      source.clear();
      startCoordinate = null;
      state = selectingStartState;
      const message = error instanceof Error ? error.message : "The terrain path could not be calculated.";
      statusElement.textContent = `${message} Select the start point.`;
      updateControls();
    } finally {
      if (currentRequestId === requestId) {
        abortController = null;
      }
    }
  }

  function renderResult(geoJsonFeature) {
    const projection = map.getView().getProjection();
    const properties = geoJsonFeature?.properties;
    if (geoJsonFeature?.geometry?.type !== "LineString"
        || !Array.isArray(properties?.snappedStart)
        || !Array.isArray(properties?.snappedEnd)
        || !Number.isFinite(properties?.distance3DMetres)
        || !Number.isInteger(properties?.segmentCount)) {
      throw new Error("The terrain path service returned invalid GeoJSON.");
    }

    const routeFeature = new GeoJSON().readFeature(geoJsonFeature, {
      dataProjection: "EPSG:4326",
      featureProjection: projection
    });
    routeFeature.set("terrainPathRole", "route");
    const snappedStart = transform(properties.snappedStart, "EPSG:4326", projection);
    const snappedEnd = transform(properties.snappedEnd, "EPSG:4326", projection);

    source.clear();
    source.addFeatures([
      routeFeature,
      createMarkerFeature(snappedStart, "start"),
      createMarkerFeature(snappedEnd, "end")
    ]);
    resultGeoJson = geoJsonFeature;
  }

  function cancelSelection() {
    if (state !== selectingStartState && state !== selectingEndState && state !== calculatingState) {
      return false;
    }
    reset();
    return true;
  }

  function reset() {
    requestId += 1;
    abortController?.abort();
    abortController = null;
    startCoordinate = null;
    resultGeoJson = null;
    selectedMaximumSlopeDegrees = null;
    state = idleState;
    source.clear();
    statusElement.textContent = "";
    updateControls();
  }

  function setElevationMode(enabled) {
    isElevationMode = enabled;
    if (!enabled) {
      reset();
    }
    layer.setVisible(enabled);
    updateControls();
  }

  function updateControls() {
    actionButton.disabled = !elevationAvailable || !isElevationMode;
    actionButton.textContent = state === idleState
      ? "Find shortest terrain path"
      : state === resultState ? "Clear terrain path" : "Cancel terrain path";
    actionButton.setAttribute("aria-pressed", String(
      state === selectingStartState || state === selectingEndState || state === calculatingState
    ));
    map.getTargetElement().classList.toggle(
      "is-terrain-path-selecting",
      state === selectingStartState || state === selectingEndState
    );
    if (state !== resultState) {
      map.getTargetElement().classList.remove("is-terrain-path-route-hover");
    }
  }

  return { cancelSelection, handleMapClick, reset, setElevationMode };
}

function createMarkerFeature(coordinate, role) {
  return new Feature({
    geometry: new Point(coordinate),
    terrainPathRole: role
  });
}

function createMarkerStyle(label, fillColor) {
  return new Style({
    image: new CircleStyle({
      radius: 12,
      fill: new Fill({ color: fillColor }),
      stroke: new Stroke({ color: "#ffffff", width: 3 })
    }),
    text: new Text({
      text: label,
      fill: new Fill({ color: "#ffffff" }),
      font: "700 12px Arial, sans-serif"
    })
  });
}