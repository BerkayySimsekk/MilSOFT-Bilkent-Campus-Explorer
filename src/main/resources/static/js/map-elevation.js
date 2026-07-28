import WebGLTileLayer from "https://cdn.jsdelivr.net/npm/ol@10.6.1/layer/WebGLTile.js";
import GeoTIFF from "https://cdn.jsdelivr.net/npm/ol@10.6.1/source/GeoTIFF.js";

const elevationConfigurationUrl = "/data/elevation/elevation-config.json";

export function createElevationLayer() {
  return new WebGLTileLayer({
    visible: false,
    opacity: 0.72,
    zIndex: 1,
    style: {
      variables: {
        minimumElevation: 0,
        mediumLowElevation: 0.35,
        mediumElevation: 0.6,
        highElevation: 0.82,
        maximumElevation: 1
      },
      color: [
        "case",
        ["==", ["band", 2], 0],
        [0, 0, 0, 0],
        [
          "interpolate",
          ["linear"],
          ["band", 1],
          ["var", "minimumElevation"], [17, 78, 45, 1],
          ["var", "mediumLowElevation"], [104, 166, 92, 1],
          ["var", "mediumElevation"], [220, 202, 92, 1],
          ["var", "highElevation"], [156, 119, 74, 1],
          ["var", "maximumElevation"], [245, 246, 242, 1]
        ]
      ]
    }
  });
}

export async function configureElevationLayer(elevationLayer) {
  try {
    const response = await fetch(elevationConfigurationUrl);
    if (!response.ok) {
      throw new Error(response.status === 404
        ? "No elevation dataset has been prepared yet."
        : `Elevation configuration request failed with status ${response.status}.`);
    }

    const configuration = await response.json();
    if (configuration.available === false) {
      return {
        available: false,
        message: configuration.message || "No elevation dataset has been prepared yet."
      };
    }
    validateElevationConfiguration(configuration);
    const elevationRange = configuration.maximumElevation - configuration.minimumElevation;
    const styleRange = elevationRange > 0 ? elevationRange : 1;

    elevationLayer.updateStyleVariables({
      minimumElevation: configuration.minimumElevation,
      mediumLowElevation: configuration.minimumElevation + styleRange * 0.35,
      mediumElevation: configuration.minimumElevation + styleRange * 0.6,
      highElevation: configuration.minimumElevation + styleRange * 0.82,
      maximumElevation: configuration.minimumElevation + styleRange
    });

    const elevationSource = new GeoTIFF({
      sources: [{ url: configuration.rasterUrl }],
      normalize: false,
      interpolate: true,
      wrapX: false
    });
    elevationSource.on("error", (event) => console.error("Unable to load elevation raster:", event));
    elevationLayer.setSource(elevationSource);

    return { available: true, ...configuration };
  } catch (error) {
    console.info("Elevation map is unavailable until real terrain data is prepared:", error);
    return {
      available: false,
      message: error instanceof Error ? error.message : "Elevation data is unavailable."
    };
  }
}

function validateElevationConfiguration(configuration) {
  if (!configuration
      || typeof configuration.rasterUrl !== "string"
      || !configuration.rasterUrl
      || !Number.isFinite(configuration.minimumElevation)
      || !Number.isFinite(configuration.maximumElevation)
      || configuration.maximumElevation < configuration.minimumElevation
      || configuration.unit !== "metres") {
    throw new Error("Elevation configuration is invalid.");
  }
}