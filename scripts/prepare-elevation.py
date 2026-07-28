#!/usr/bin/env python3
import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path

try:
    import numpy as np
    from osgeo import gdal
except ImportError as exception:
    raise SystemExit(
        "GDAL Python bindings and NumPy are required. Run this script from a GDAL-enabled environment."
    ) from exception


NO_DATA_VALUE = -32768.0
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BOUNDARY = PROJECT_ROOT / "src/main/resources/static/data/campus-boundary.geojson"
DEFAULT_OUTPUT_DIRECTORY = PROJECT_ROOT / "src/main/resources/static/data/elevation"


def parse_arguments():
    parser = argparse.ArgumentParser(
        description="Crop real terrain data to Bilkent campus and prepare browser/API elevation assets."
    )
    parser.add_argument("source", type=Path, help="Source DTED/DT0/DT1/DT2 or GeoTIFF elevation file")
    parser.add_argument(
        "--boundary",
        type=Path,
        default=DEFAULT_BOUNDARY,
        help="EPSG:4326 GeoJSON crop boundary",
    )
    parser.add_argument(
        "--output-directory",
        type=Path,
        default=DEFAULT_OUTPUT_DIRECTORY,
        help="Directory for the generated COG, float samples, and configuration",
    )
    return parser.parse_args()


def prepare_elevation(source_path, boundary_path, output_directory):
    source_path = source_path.resolve()
    boundary_path = boundary_path.resolve()
    output_directory = output_directory.resolve()
    require_file(source_path, "elevation source")
    require_file(boundary_path, "crop boundary")

    gdal.UseExceptions()
    with tempfile.TemporaryDirectory(prefix="bilkent-elevation-") as temporary_directory_name:
        temporary_directory = Path(temporary_directory_name)
        cropped_path = temporary_directory / "bilkent-elevation-cropped.tif"
        cog_path = temporary_directory / "bilkent-elevation.tif"
        sample_path = temporary_directory / "bilkent-elevation.f32"
        config_path = temporary_directory / "elevation-config.json"

        crop_to_campus(source_path, boundary_path, cropped_path)
        create_cloud_optimized_geotiff(cropped_path, cog_path)
        create_sample_grid_and_configuration(cog_path, sample_path, config_path)

        output_directory.mkdir(parents=True, exist_ok=True)
        for prepared_path in (cog_path, sample_path, config_path):
            shutil.copy2(prepared_path, output_directory / prepared_path.name)

    print(f"Prepared elevation assets in {output_directory}")


def crop_to_campus(source_path, boundary_path, cropped_path):
    source_dataset = gdal.Open(str(source_path), gdal.GA_ReadOnly)
    if source_dataset is None:
        raise RuntimeError(f"GDAL could not open {source_path}")

    try:
        warped_dataset = gdal.Warp(
            str(cropped_path),
            source_dataset,
            options=gdal.WarpOptions(
                format="GTiff",
                cutlineDSName=str(boundary_path),
                cropToCutline=True,
                dstSRS="EPSG:4326",
                resampleAlg="near",
                dstNodata=NO_DATA_VALUE,
                outputType=gdal.GDT_Float32,
                multithread=True,
                creationOptions=["TILED=YES", "COMPRESS=DEFLATE", "BIGTIFF=IF_SAFER"],
            ),
        )
        if warped_dataset is None:
            raise RuntimeError("GDAL did not create the cropped elevation raster.")
        warped_dataset.FlushCache()
        warped_dataset = None
    finally:
        source_dataset = None


def create_cloud_optimized_geotiff(cropped_path, cog_path):
    cog_dataset = gdal.Translate(
        str(cog_path),
        str(cropped_path),
        options=gdal.TranslateOptions(
            format="COG",
            creationOptions=[
                "COMPRESS=DEFLATE",
                "BLOCKSIZE=512",
                "RESAMPLING=NEAREST",
                "OVERVIEWS=AUTO",
                "BIGTIFF=IF_SAFER",
            ],
        ),
    )
    if cog_dataset is None:
        raise RuntimeError("This GDAL installation could not create a Cloud Optimized GeoTIFF.")
    cog_dataset.FlushCache()
    cog_dataset = None


def create_sample_grid_and_configuration(cog_path, sample_path, config_path):
    dataset = gdal.Open(str(cog_path), gdal.GA_ReadOnly)
    if dataset is None:
        raise RuntimeError("GDAL could not reopen the prepared elevation raster.")

    try:
        band = dataset.GetRasterBand(1)
        samples = band.ReadAsArray().astype(np.float32, copy=False)
        source_no_data = band.GetNoDataValue()
        valid_samples = np.isfinite(samples)
        if source_no_data is not None:
            valid_samples &= samples != np.float32(source_no_data)
        if not np.any(valid_samples):
            raise RuntimeError("The campus crop contains no valid elevation samples.")

        minimum_elevation = float(np.min(samples[valid_samples]))
        maximum_elevation = float(np.max(samples[valid_samples]))
        query_samples = samples.copy()
        query_samples[~valid_samples] = np.nan
        query_samples.astype("<f4", copy=False).tofile(sample_path)

        configuration = {
            "rasterUrl": "/data/elevation/bilkent-elevation.tif",
            "width": dataset.RasterXSize,
            "height": dataset.RasterYSize,
            "geoTransform": list(dataset.GetGeoTransform()),
            "minimumElevation": minimum_elevation,
            "maximumElevation": maximum_elevation,
            "unit": "metres",
        }
        config_path.write_text(json.dumps(configuration, indent=2) + "\n", encoding="ascii")
    finally:
        dataset = None


def require_file(path, label):
    if not path.is_file():
        raise FileNotFoundError(f"The {label} does not exist: {path}")


def main():
    arguments = parse_arguments()
    try:
        prepare_elevation(arguments.source, arguments.boundary, arguments.output_directory)
    except (FileNotFoundError, RuntimeError) as exception:
        print(f"Elevation preparation failed: {exception}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())