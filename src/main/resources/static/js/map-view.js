import Map from "https://cdn.jsdelivr.net/npm/ol@10.6.1/Map.js";
import View from "https://cdn.jsdelivr.net/npm/ol@10.6.1/View.js";
import TileLayer from "https://cdn.jsdelivr.net/npm/ol@10.6.1/layer/Tile.js";
import OSM from "https://cdn.jsdelivr.net/npm/ol@10.6.1/source/OSM.js";
import Overlay from "https://cdn.jsdelivr.net/npm/ol@10.6.1/Overlay.js";
import Control from "https://cdn.jsdelivr.net/npm/ol@10.6.1/control/Control.js";
import { defaults as defaultInteractions } from "https://cdn.jsdelivr.net/npm/ol@10.6.1/interaction/defaults.js";

const campusMinZoom = 14;

export function createCampusMap({
  boundaryGeometry,
  popupElement,
  elevationLayer,
  terrainAvoidanceLayer,
  terrainPathLayer,
  locationLayerGroup
}) {
  let isConstrainingCampusCenter = false;
  const popupOverlay = new Overlay({
    element: popupElement,
    autoPan: {
      animation: { duration: 220 },
      margin: 24
    },
    positioning: "bottom-center",
    offset: [0, -14],
    stopEvent: true
  });
  const map = new Map({
    target: "map",
    interactions: defaultInteractions({ doubleClickZoom: false }),
    layers: [
      new TileLayer({ source: new OSM() }),
      elevationLayer,
      terrainAvoidanceLayer,
      terrainPathLayer,
      locationLayerGroup
    ],
    view: new View({
      center: boundaryGeometry.getInteriorPoint().getCoordinates().slice(0, 2),
      minZoom: campusMinZoom,
      zoom: 16
    }),
    overlays: [popupOverlay]
  });
  const zoomLevelElement = document.createElement("div");
  zoomLevelElement.className = "zoom-level ol-unselectable ol-control";
  zoomLevelElement.setAttribute("role", "status");
  zoomLevelElement.setAttribute("aria-live", "polite");
  map.addControl(new Control({ element: zoomLevelElement }));

  const updateZoomLevel = () => {
    const zoom = map.getView().getZoom();
    zoomLevelElement.textContent = `Zoom ${zoom?.toFixed(1) ?? "--"}`;
  };
  map.getView().on("change:resolution", updateZoomLevel);
  updateZoomLevel();

  map.getView().on("change:center", () => {
    if (isConstrainingCampusCenter) {
      return;
    }

    const view = map.getView();
    const center = view.getCenter();
    if (!center || boundaryGeometry.intersectsCoordinate(center)) {
      return;
    }

    isConstrainingCampusCenter = true;
    view.setCenter(boundaryGeometry.getClosestPoint(center));
    isConstrainingCampusCenter = false;
  });

  return { map, popupOverlay };
}