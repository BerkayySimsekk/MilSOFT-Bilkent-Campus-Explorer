import Feature from "https://cdn.jsdelivr.net/npm/ol@10.6.1/Feature.js";
import CircleGeometry from "https://cdn.jsdelivr.net/npm/ol@10.6.1/geom/Circle.js";
import LineString from "https://cdn.jsdelivr.net/npm/ol@10.6.1/geom/LineString.js";
import PolygonGeometry from "https://cdn.jsdelivr.net/npm/ol@10.6.1/geom/Polygon.js";
import { getCenter } from "https://cdn.jsdelivr.net/npm/ol@10.6.1/extent.js";
import Draw from "https://cdn.jsdelivr.net/npm/ol@10.6.1/interaction/Draw.js";

export function createDrawingController({
  map,
  campusBoundaryGeometry,
  featureTypeSelect,
  drawingPreviewSource,
  drawingPreviewLayer,
  drawingPreviewLineStyle,
  areaFilterLayer,
  isAreaSelectionActive,
  isLocationMode,
  onDrawingStart,
  onFeatureSelected,
  onCustomFeatureRequested
}) {
  let pendingLineStartCoordinate = null;
  let pendingCircleCenterCoordinate = null;
  let pendingPolygonCoordinates = [];
  let freehandDrawInteraction = null;
  let freehandDraftFeature = null;

  function isIgnoredFeatureLayer(layer) {
    return layer === drawingPreviewLayer || layer === areaFilterLayer;
  }

  function getClickedFeature(pixel) {
    return map.forEachFeatureAtPixel(
      pixel,
      (feature, layer) => isIgnoredFeatureLayer(layer) ? undefined : feature
    );
  }

  function initialize() {
    updateFreehandDrawing();

    map.on("singleclick", (event) => {
      if (!isLocationMode() || isAreaSelectionActive()) {
        return;
      }

      const clickedFeature = getClickedFeature(event.pixel);

      if (clickedFeature) {
        cancelDrawing();
        onFeatureSelected(clickedFeature, { animate: false });
      } else if (featureTypeSelect.value === "LineString") {
        handleLineMapClick(event.coordinate);
      } else if (featureTypeSelect.value === "Circle") {
        handleCircleMapClick(event.coordinate);
      } else if (featureTypeSelect.value === "Polygon") {
        handlePolygonMapClick(event.coordinate);
      } else if (campusBoundaryGeometry.intersectsCoordinate(event.coordinate)) {
        onCustomFeatureRequested([event.coordinate], "Point");
      }
    });

    map.on("dblclick", (event) => {
      if (!isLocationMode() || isAreaSelectionActive()) {
        return;
      }

      if (featureTypeSelect.value !== "Polygon" || pendingPolygonCoordinates.length === 0) {
        return;
      }

      event.preventDefault();
      const clickedFeature = getClickedFeature(event.pixel);
      if (clickedFeature) {
        cancelDrawing();
        onFeatureSelected(clickedFeature, { animate: false });
        return;
      }

      completePolygon(event.coordinate);
    });

    map.on("pointermove", (event) => {
      if (event.dragging || !isLocationMode() || isAreaSelectionActive()) {
        map.getTargetElement().style.cursor = "";
        return;
      }

      if (pendingLineStartCoordinate) {
        updateLinePreview(event.coordinate);
      }
      if (pendingCircleCenterCoordinate) {
        updateCirclePreview(event.coordinate);
      }
      if (pendingPolygonCoordinates.length > 0) {
        updatePolygonPreview(event.coordinate);
      }

      const hasFeature = map.hasFeatureAtPixel(event.pixel, {
        layerFilter: (layer) => !isIgnoredFeatureLayer(layer)
      });
      map.getTargetElement().style.cursor = hasFeature ? "pointer" : "";
    });
  }

  function handleLineMapClick(coordinates) {
    if (!campusBoundaryGeometry.intersectsCoordinate(coordinates)) {
      return;
    }

    if (!pendingLineStartCoordinate) {
      onDrawingStart();
      pendingLineStartCoordinate = coordinates;
      updateLinePreview(coordinates);
      return;
    }

    const lineCoordinates = [pendingLineStartCoordinate, coordinates];
    updateLinePreview(coordinates);
    finishDrawingPreview();
    onCustomFeatureRequested(lineCoordinates, "LineString");
  }

  function updateLinePreview(currentCoordinate) {
    drawingPreviewSource.clear();
    drawingPreviewSource.addFeature(new Feature(new LineString([pendingLineStartCoordinate, currentCoordinate])));
  }

  function handleCircleMapClick(coordinates) {
    if (!campusBoundaryGeometry.intersectsCoordinate(coordinates)) {
      return;
    }

    if (!pendingCircleCenterCoordinate) {
      onDrawingStart();
      pendingCircleCenterCoordinate = coordinates;
      updateCirclePreview(coordinates);
      return;
    }

    const circleCenterCoordinate = pendingCircleCenterCoordinate;
    const circleCoordinates = createCircleCoordinates(circleCenterCoordinate, coordinates);
    updateCirclePreview(coordinates);
    finishDrawingPreview();
    onCustomFeatureRequested(circleCoordinates, "Circle", circleCenterCoordinate);
  }

  function updateCirclePreview(currentCoordinate) {
    const radius = Math.hypot(
      currentCoordinate[0] - pendingCircleCenterCoordinate[0],
      currentCoordinate[1] - pendingCircleCenterCoordinate[1]
    );
    drawingPreviewSource.clear();
    drawingPreviewSource.addFeature(new Feature(new CircleGeometry(pendingCircleCenterCoordinate, radius)));
  }

  function createCircleCoordinates(centerCoordinate, edgeCoordinate) {
    const radius = Math.hypot(
      edgeCoordinate[0] - centerCoordinate[0],
      edgeCoordinate[1] - centerCoordinate[1]
    );
    const segmentCount = 64;
    const circleCoordinates = [];

    for (let index = 0; index < segmentCount; index += 1) {
      const angle = (2 * Math.PI * index) / segmentCount;
      circleCoordinates.push([
        centerCoordinate[0] + radius * Math.cos(angle),
        centerCoordinate[1] + radius * Math.sin(angle)
      ]);
    }
    circleCoordinates.push([...circleCoordinates[0]]);
    return circleCoordinates;
  }

  function handlePolygonMapClick(coordinates) {
    if (!campusBoundaryGeometry.intersectsCoordinate(coordinates)) {
      return;
    }

    if (pendingPolygonCoordinates.length === 0) {
      onDrawingStart();
    }

    pendingPolygonCoordinates.push(coordinates);
    updatePolygonPreview(coordinates);
  }

  function updatePolygonPreview(currentCoordinate) {
    const previewCoordinates = [...pendingPolygonCoordinates, currentCoordinate];
    drawingPreviewSource.clear();

    if (previewCoordinates.length < 3) {
      drawingPreviewSource.addFeature(new Feature(new LineString(previewCoordinates)));
      return;
    }

    drawingPreviewSource.addFeature(new Feature(new PolygonGeometry([[
      ...previewCoordinates,
      previewCoordinates[0]
    ]])));
  }

  function completePolygon(coordinates) {
    if (!campusBoundaryGeometry.intersectsCoordinate(coordinates)) {
      return;
    }

    const polygonCoordinates = [...pendingPolygonCoordinates];
    if (!coordinatesMatch(polygonCoordinates.at(-1), coordinates)) {
      polygonCoordinates.push(coordinates);
    }
    if (polygonCoordinates.length < 3) {
      return;
    }

    polygonCoordinates.push([...polygonCoordinates[0]]);
    const polygonGeometry = new PolygonGeometry([polygonCoordinates]);
    const popupCoordinate = getCenter(polygonGeometry.getExtent());
    drawingPreviewSource.clear();
    drawingPreviewSource.addFeature(new Feature(polygonGeometry));
    finishDrawingPreview();
    onCustomFeatureRequested(polygonCoordinates, "Polygon", popupCoordinate);
  }

  function coordinatesMatch(firstCoordinate, secondCoordinate) {
    return firstCoordinate?.[0] === secondCoordinate?.[0] && firstCoordinate?.[1] === secondCoordinate?.[1];
  }

  // Ends an active draw without removing the completed dotted preview under the add form.
  function finishDrawingPreview() {
    pendingLineStartCoordinate = null;
    pendingCircleCenterCoordinate = null;
    pendingPolygonCoordinates = [];
    stopFreehandDrawing();
  }

  function cancelDrawing() {
    finishDrawingPreview();
    drawingPreviewSource.clear();
    freehandDraftFeature = null;
  }

  function updateFreehandDrawing() {
    if (!isLocationMode() || featureTypeSelect.value !== "Freehand" || isAreaSelectionActive()) {
      stopFreehandDrawing();
      return;
    }

    if (freehandDrawInteraction) {
      return;
    }

    freehandDrawInteraction = new Draw({
      type: "LineString",
      freehand: true,
      style: drawingPreviewLineStyle,
      condition: (event) => isLocationMode() && !isAreaSelectionActive() && !map.hasFeatureAtPixel(event.pixel, {
        layerFilter: (layer) => !isIgnoredFeatureLayer(layer)
      })
    });
    freehandDrawInteraction.on("drawend", (event) => {
      const coordinates = event.feature.getGeometry().getCoordinates();
      stopFreehandDrawing();
      drawingPreviewSource.clear();
      freehandDraftFeature = new Feature(new LineString(coordinates));
      drawingPreviewSource.addFeature(freehandDraftFeature);
      onCustomFeatureRequested(coordinates, "Freehand");
    });
    map.addInteraction(freehandDrawInteraction);
  }

  function stopFreehandDrawing() {
    if (!freehandDrawInteraction) {
      return;
    }

    freehandDrawInteraction.abortDrawing();
    map.removeInteraction(freehandDrawInteraction);
    freehandDrawInteraction = null;
  }

  function clearFreehandDraft() {
    if (!freehandDraftFeature) {
      return;
    }

    drawingPreviewSource.removeFeature(freehandDraftFeature);
    freehandDraftFeature = null;
  }

  return {
    initialize,
    cancelDrawing,
    clearFreehandDraft,
    stopFreehandDrawing,
    updateFreehandDrawing
  };
}