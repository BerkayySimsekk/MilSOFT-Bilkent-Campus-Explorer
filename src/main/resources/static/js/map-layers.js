import Feature from "https://cdn.jsdelivr.net/npm/ol@10.6.1/Feature.js";
import Point from "https://cdn.jsdelivr.net/npm/ol@10.6.1/geom/Point.js";
import LayerGroup from "https://cdn.jsdelivr.net/npm/ol@10.6.1/layer/Group.js";
import VectorLayer from "https://cdn.jsdelivr.net/npm/ol@10.6.1/layer/Vector.js";
import ClusterSource from "https://cdn.jsdelivr.net/npm/ol@10.6.1/source/Cluster.js";
import VectorSource from "https://cdn.jsdelivr.net/npm/ol@10.6.1/source/Vector.js";
import Style from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Style.js";
import CircleStyle from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Circle.js";
import Fill from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Fill.js";
import Stroke from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Stroke.js";
import Text from "https://cdn.jsdelivr.net/npm/ol@10.6.1/style/Text.js";

const categoryColors = {
  Building: "#2563eb",
  Food: "#ea580c",
  Health: "#dc2626",
  Parking: "#7c3aed",
  Transport: "#059669",
  Services: "#0f766e",
  Recreation: "#65a30d",
  Culture: "#c026d3",
  Custom: "#db2777",
  default: "#475569"
};
export const minLocationZoom = 15;
const maxLocationZoom = 20;
const pointMarkerRadius = 6;
const pointMarkerOutlineWidth = 2;
const pointMarkerDiameter = 2 * pointMarkerRadius + pointMarkerOutlineWidth;

function createPointStyle(color, radius, outlineColor, outlineWidth) {
  return new Style({
    image: new CircleStyle({
      radius,
      fill: new Fill({ color }),
      stroke: new Stroke({ color: outlineColor, width: outlineWidth })
    })
  });
}

function createLineStyle(color, width, lineDash) {
  return new Style({
    stroke: new Stroke({ color, width, lineDash })
  });
}

function createAreaStyle(outlineColor, outlineWidth, fillColor, lineDash) {
  return new Style({
    fill: new Fill({ color: fillColor }),
    stroke: new Stroke({ color: outlineColor, width: outlineWidth, lineDash })
  });
}

const normalPointStyles = Object.fromEntries(
  Object.entries(categoryColors).map(([category, color]) => [
    category,
    createPointStyle(color, pointMarkerRadius, "#ffffff", pointMarkerOutlineWidth)
  ])
);
const normalLineStyles = Object.fromEntries(
  Object.entries(categoryColors).map(([category, color]) => [
    category,
    createLineStyle(color, 4)
  ])
);
const normalAreaStyles = Object.fromEntries(
  Object.entries(categoryColors).map(([category, color]) => [
    category,
    createAreaStyle(color, 3, `${color}33`)
  ])
);
const selectedPointStyle = createPointStyle("#facc15", 9, "#1e293b", 3);
const selectedLineStyle = createLineStyle("#facc15", 6);
const selectedAreaStyle = createAreaStyle("#facc15", 5, "rgba(250, 204, 21, 0.28)");
const drawingPreviewLineStyle = createLineStyle("#1677be", 3, [8, 6]);
const drawingPreviewCircleStyle = createAreaStyle("#1677be", 3, "rgba(22, 119, 190, 0.12)", [8, 6]);
const areaFilterStyle = createAreaStyle("#1677be", 3, "rgba(22, 119, 190, 0.18)", [10, 6]);
const clusterStyles = new Map();

class ConnectedComponentClusterSource extends ClusterSource {
  cluster() {
    if (this.resolution === undefined || !this.source) {
      return;
    }

    const features = this.source.getFeatures();
    const coordinates = features.map((feature) => this.geometryFunction(feature)?.getCoordinates() ?? null);
    const visited = new Array(features.length).fill(false);
    const mapDistance = this.distance * this.resolution;
    const squaredMapDistance = mapDistance * mapDistance;

    for (let index = 0; index < features.length; index += 1) {
      if (visited[index] || !coordinates[index]) {
        continue;
      }

      visited[index] = true;
      const pendingIndexes = [index];
      const componentFeatures = [];
      const centroid = [0, 0];

      for (let pendingIndex = 0; pendingIndex < pendingIndexes.length; pendingIndex += 1) {
        const currentIndex = pendingIndexes[pendingIndex];
        const currentCoordinates = coordinates[currentIndex];
        componentFeatures.push(features[currentIndex]);
        centroid[0] += currentCoordinates[0];
        centroid[1] += currentCoordinates[1];

        for (let candidateIndex = 0; candidateIndex < features.length; candidateIndex += 1) {
          const candidateCoordinates = coordinates[candidateIndex];
          if (visited[candidateIndex] || !candidateCoordinates) {
            continue;
          }

          const deltaX = currentCoordinates[0] - candidateCoordinates[0];
          const deltaY = currentCoordinates[1] - candidateCoordinates[1];
          if (deltaX * deltaX + deltaY * deltaY <= squaredMapDistance) {
            visited[candidateIndex] = true;
            pendingIndexes.push(candidateIndex);
          }
        }
      }

      centroid[0] /= componentFeatures.length;
      centroid[1] /= componentFeatures.length;
      this.features.push(new Feature({
        geometry: new Point(centroid),
        features: componentFeatures
      }));
    }
  }
}

function createDrawingPreviewStyle(feature) {
  const geometryType = feature.getGeometry()?.getType();
  return geometryType === "Circle" || geometryType === "Polygon"
    ? drawingPreviewCircleStyle : drawingPreviewLineStyle;
}

function createFeatureStyle(feature, selectedFeature) {
  const geometryType = feature.getGeometry()?.getType();
  const category = feature.get("category");

  if (geometryType === "LineString") {
    return feature === selectedFeature
      ? selectedLineStyle
      : normalLineStyles[category] || normalLineStyles.default;
  }

  if (geometryType === "Polygon") {
    return feature === selectedFeature
      ? selectedAreaStyle
      : normalAreaStyles[category] || normalAreaStyles.default;
  }

  if (feature === selectedFeature) {
    return selectedPointStyle;
  }

  return normalPointStyles[category] || normalPointStyles.default;
}

function createClusterStyle(clusterFeature, selectedFeature) {
  const features = clusterFeature.get("features") || [];
  if (features.length === 1) {
    return createFeatureStyle(features[0], selectedFeature);
  }

  const count = features.length;
  if (!clusterStyles.has(count)) {
    clusterStyles.set(count, new Style({
      image: new CircleStyle({
        radius: 11,
        fill: new Fill({ color: "#102a43" }),
        stroke: new Stroke({ color: "#ffffff", width: 2 })
      }),
      text: new Text({
        text: String(count),
        fill: new Fill({ color: "#ffffff" }),
        font: "700 12px Arial, sans-serif"
      })
    }));
  }

  return clusterStyles.get(count);
}

export function createMapLayers(getSelectedFeature) {
  const locationCategories = Object.keys(categoryColors).filter((category) => category !== "default");
  const vectorSources = Object.fromEntries(
    locationCategories.map((category) => [category, new VectorSource()])
  );
  const vectorLayers = Object.fromEntries(
    locationCategories.map((category, index) => [
      category,
      new VectorLayer({
        source: vectorSources[category],
        style: (feature) => createFeatureStyle(feature, getSelectedFeature()),
        minZoom: minLocationZoom,
        maxZoom: maxLocationZoom,
        zIndex: index + 1
      })
    ])
  );
  const pointSource = new VectorSource();
  const clusterSource = new ConnectedComponentClusterSource({
    distance: pointMarkerDiameter,
    source: pointSource
  });
  const clusterLayer = new VectorLayer({
    source: clusterSource,
    style: (feature) => createClusterStyle(feature, getSelectedFeature()),
    minZoom: minLocationZoom,
    maxZoom: maxLocationZoom,
    zIndex: locationCategories.length + 1
  });
  const drawingPreviewSource = new VectorSource();
  const drawingPreviewLayer = new VectorLayer({
    source: drawingPreviewSource,
    style: createDrawingPreviewStyle,
    zIndex: 100
  });
  const areaFilterSource = new VectorSource();
  const areaFilterLayer = new VectorLayer({
    source: areaFilterSource,
    style: areaFilterStyle,
    zIndex: 0.5
  });
  const locationLayerGroup = new LayerGroup({
    layers: [
      areaFilterLayer,
      ...locationCategories.map((category) => vectorLayers[category]),
      clusterLayer,
      drawingPreviewLayer
    ],
    zIndex: 2
  });

  return {
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
  };
}