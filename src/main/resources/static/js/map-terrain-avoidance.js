import GeoJSON from "https://cdn.jsdelivr.net/npm/ol@10.6.1/format/GeoJSON.js";
import Draw from "https://cdn.jsdelivr.net/npm/ol@10.6.1/interaction/Draw.js";
import VectorLayer from "https://cdn.jsdelivr.net/npm/ol@10.6.1/layer/Vector.js";
import { toLonLat } from "https://cdn.jsdelivr.net/npm/ol@10.6.1/proj.js";
import VectorSource from "https://cdn.jsdelivr.net/npm/ol@10.6.1/source/Vector.js";
import Stroke from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Stroke.js";
import Style from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Style.js";
import booleanWithin from "https://cdn.jsdelivr.net/npm/@turf/boolean-within@7.2.0/+esm";

const barrierStyles = [
  new Style({ stroke: new Stroke({ color: "rgba(255, 235, 230, 0.96)", width: 6 }) }),
  new Style({ stroke: new Stroke({ color: "#7f1d1d", width: 3 }) })
];

export function createTerrainAvoidanceLayer() {
  const source = new VectorSource({ wrapX: false });
  const layer = new VectorLayer({
    source,
    visible: false,
    zIndex: 40,
    style: barrierStyles
  });
  return { source, layer };
}

export function createTerrainAvoidanceController({
  map,
  layer,
  source,
  campusBoundaryGeometry,
  drawButton,
  clearButton,
  statusElement,
  onDrawingStart,
  onBarriersChanged
}) {
  const geoJsonFormat = new GeoJSON();
  let drawInteraction = null;
  let isElevationMode = false;

  drawButton.addEventListener("click", () => {
    if (drawInteraction) {
      stopDrawing();
    } else {
      startDrawing();
    }
  });
  clearButton.addEventListener("click", clear);
  updateControls();

  function startDrawing() {
    if (!isElevationMode || drawInteraction) {
      return;
    }

    onDrawingStart();
    drawInteraction = new Draw({
      type: "LineString",
      freehand: true,
      stopClick: true,
      style: barrierStyles,
      condition: (event) => campusBoundaryGeometry.intersectsCoordinate(event.coordinate),
      finishCondition: (event) => campusBoundaryGeometry.intersectsCoordinate(event.coordinate)
    });
    drawInteraction.on("drawstart", () => {
      statusElement.textContent = "Drawing avoidance barriers...";
    });
    drawInteraction.on("drawend", (event) => {
      const geometry = event.feature.getGeometry();
      if (!isValidBarrier(geometry)) {
        statusElement.textContent = "Draw a line with at least two distinct points entirely inside campus.";
        return;
      }

      source.addFeature(event.feature);
      onBarriersChanged();
      statusElement.textContent = "Drawing avoidance barriers...";
      updateControls();
    });
    map.addInteraction(drawInteraction);
    statusElement.textContent = "Drawing avoidance barriers...";
    updateControls();
  }

  function isValidBarrier(geometry) {
    if (geometry?.getType() !== "LineString") {
      return false;
    }

    const coordinates = geometry.getCoordinates();
    const firstCoordinate = coordinates[0];
    const hasDistinctCoordinate = coordinates.some((coordinate) =>
      coordinate[0] !== firstCoordinate?.[0] || coordinate[1] !== firstCoordinate?.[1]
    );
    if (coordinates.length < 2 || !hasDistinctCoordinate) {
      return false;
    }

    const barrierGeoJson = geoJsonFormat.writeGeometryObject(geometry);
    const boundaryGeoJson = geoJsonFormat.writeGeometryObject(campusBoundaryGeometry);
    return booleanWithin(barrierGeoJson, boundaryGeoJson);
  }

  function stopDrawing() {
    if (!drawInteraction) {
      return false;
    }

    const completedInteraction = drawInteraction;
    drawInteraction = null;
    completedInteraction.abortDrawing();
    map.removeInteraction(completedInteraction);
    updateControls();
    return true;
  }

  function clear() {
    if (source.isEmpty()) {
      return;
    }

    source.clear();
    onBarriersChanged();
    updateControls();
  }

  function getAvoidanceBarriers() {
    const projection = map.getView().getProjection();
    return source.getFeatures().map((feature) =>
      feature.getGeometry().getCoordinates().map((coordinate) =>
        toLonLat(coordinate, projection)
      )
    );
  }

  function setElevationMode(enabled) {
    isElevationMode = enabled;
    if (!enabled) {
      stopDrawing();
    }
    layer.setVisible(enabled);
    updateControls();
  }

  function updateControls() {
    const barrierCount = source.getFeatures().length;
    drawButton.disabled = !isElevationMode;
    drawButton.textContent = drawInteraction ? "Stop drawing" : "Draw barriers to avoid";
    drawButton.setAttribute("aria-pressed", String(Boolean(drawInteraction)));
    clearButton.disabled = barrierCount === 0;
    if (!drawInteraction) {
      statusElement.textContent = barrierCount === 0
        ? "No avoidance barriers"
        : `${barrierCount} avoidance ${barrierCount === 1 ? "barrier" : "barriers"}`;
    }
    map.getTargetElement().classList.toggle("is-terrain-avoidance-drawing", Boolean(drawInteraction));
  }

  return {
    clear,
    getAvoidanceBarriers,
    isDrawing: () => Boolean(drawInteraction),
    setElevationMode,
    stopDrawing
  };
}