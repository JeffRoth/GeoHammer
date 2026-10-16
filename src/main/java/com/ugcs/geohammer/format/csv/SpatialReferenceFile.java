package com.ugcs.geohammer.format.csv;

import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.CoordinatePair;
import com.ugcs.geohammer.model.Semantic;
import com.ugcs.geohammer.model.SpatialReference;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class SpatialReferenceFile {

    private static final String SUFFIX = ".geohammer-spatial.properties";

    private SpatialReferenceFile() {
    }

    static void load(File dataFile, ColumnSchema schema) throws IOException {
        File source = sidecar(dataFile);
        if (!source.isFile()) {
            inferGeographicCoordinates(schema);
            return;
        }
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(source)) {
            properties.load(input);
        }
        try {
            SpatialReference reference = read(properties, schema);
            schema.setSpatialReference(reference);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid spatial-reference metadata in " + source.getName(), e);
        }
    }

    static void save(File dataFile, @Nullable ColumnSchema schema) throws IOException {
        if (schema == null || schema.getSpatialReference() == null) {
            return;
        }
        SpatialReference reference = schema.getSpatialReference();
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("pair.count", Integer.toString(reference.coordinatePairs().size()));
        for (int index = 0; index < reference.coordinatePairs().size(); index++) {
            CoordinatePair pair = reference.coordinatePairs().get(index);
            properties.setProperty("pair." + index + ".x", pair.xSeries());
            properties.setProperty("pair." + index + ".y", pair.ySeries());
            properties.setProperty("pair." + index + ".epsg", Integer.toString(pair.epsgCode()));
        }
        properties.setProperty("active", Integer.toString(reference.coordinatePairs().indexOf(reference.activePair())));
        properties.setProperty("geographic", Integer.toString(reference.coordinatePairs().indexOf(reference.geographicPair())));
        try (FileOutputStream output = new FileOutputStream(sidecar(dataFile))) {
            properties.store(output, "GeoHammer coordinate reference metadata");
        }
    }

    private static SpatialReference read(Properties properties, ColumnSchema schema) {
        int count = parseInt(properties, "pair.count");
        List<CoordinatePair> pairs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String x = require(properties, "pair." + index + ".x");
            String y = require(properties, "pair." + index + ".y");
            if (schema.getColumn(x) == null || schema.getColumn(y) == null) {
                throw new IllegalArgumentException("Coordinate metadata references unavailable columns");
            }
            pairs.add(new CoordinatePair(x, y, parseInt(properties, "pair." + index + ".epsg")));
        }
        CoordinatePair active = indexedPair(pairs, parseInt(properties, "active"));
        CoordinatePair geographic = indexedPair(pairs, parseInt(properties, "geographic"));
        return new SpatialReference(pairs, active, geographic);
    }

    private static void inferGeographicCoordinates(ColumnSchema schema) {
        String longitude = schema.getHeaderBySemantic(Semantic.LONGITUDE.getName());
        String latitude = schema.getHeaderBySemantic(Semantic.LATITUDE.getName());
        if (longitude == null || latitude == null) {
            return;
        }
        CoordinatePair pair = new CoordinatePair(longitude, latitude, 4326);
        schema.setSpatialReference(new SpatialReference(List.of(pair), pair, pair));
    }

    private static CoordinatePair indexedPair(List<CoordinatePair> pairs, int index) {
        if (index == -1) {
            return null;
        }
        if (index < 0 || index >= pairs.size()) {
            throw new IllegalArgumentException("Coordinate-pair index is out of range");
        }
        return pairs.get(index);
    }

    private static int parseInt(Properties properties, String name) {
        try {
            return Integer.parseInt(require(properties, name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + name, e);
        }
    }

    private static String require(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private static File sidecar(File dataFile) {
        return new File(dataFile.getPath() + SUFFIX);
    }
}
