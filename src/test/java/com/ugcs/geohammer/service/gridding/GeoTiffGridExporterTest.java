package com.ugcs.geohammer.service.gridding;

import com.ugcs.geohammer.map.layer.GridLayer;
import com.ugcs.geohammer.model.LatLon;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoTiffGridExporterTest {

    @Test
    void exportsGeoTiff() throws Exception {
        GridLayer.Grid grid = new GridLayer.Grid(
                "MAG",
                new float[][] {{1, 2}, {3, Float.NaN}},
                new float[0],
                new LatLon(44, -74),
                new LatLon(45, -73),
                new GridCoordinates(-8237642, 5465442, -8126322, 5621521, 3857),
                null,
                null,
                0,
                null);
        Path target = Files.createTempFile("geohammer-grid", ".tif");
        Files.delete(target);
        target.toFile().deleteOnExit();

        new GeoTiffGridExporter(new com.ugcs.geohammer.service.coordinates.CoordinateReferenceService())
                .export(grid, target.toFile());

        assertTrue(Files.isRegularFile(target));
        assertTrue(Files.size(target) > 0);
        GeoTiffReader reader = new GeoTiffReader(target.toFile());
        try {
            GridCoverage2D coverage = reader.read(null);
            try {
                assertEquals(3857, CRS.lookupEpsgCode(coverage.getCoordinateReferenceSystem2D(), true));
                assertEquals(-8237642, coverage.getEnvelope2D().getMinX(), 0.01);
                assertEquals(5465442, coverage.getEnvelope2D().getMinY(), 0.01);
                assertEquals(-8126322, coverage.getEnvelope2D().getMaxX(), 0.01);
                assertEquals(5621521, coverage.getEnvelope2D().getMaxY(), 0.01);
                assertTrue(Float.isNaN(coverage.getRenderedImage().getData().getSampleFloat(1, 0, 0)));
            } finally {
                coverage.dispose(true);
            }
        } finally {
            reader.dispose();
        }
    }
}
