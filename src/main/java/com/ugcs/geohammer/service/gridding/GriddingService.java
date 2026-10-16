package com.ugcs.geohammer.service.gridding;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.format.SgyFile;
import com.ugcs.geohammer.math.QuickSelect;
import com.ugcs.geohammer.math.SphericalMercator;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.LatLon;
import com.ugcs.geohammer.model.Semantic;
import com.ugcs.geohammer.model.WorkingCoordinates;
import com.ugcs.geohammer.util.Check;
import com.ugcs.geohammer.util.Nulls;
import edu.mines.jtk.interp.SplinesGridder2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GriddingService {

    private static final Logger log = LoggerFactory.getLogger(GriddingService.class);

    // test showed that 200 is the point where extra iterations stop paying off
    private static final int SPLINES_MAX_ITERATIONS = 200;

    // 0 overshoots near strong anomalies, and 0.999999 becomes unstable with more iterations
    private static final double SPLINES_TENSION = 0.9999;

    public GriddingService() {
    }

    public GriddingResult runGridding(Collection<SgyFile> files, String seriesName, GriddingParams params) {
        long filteringStart = System.nanoTime();

        int epsgCode = getWorkingEpsg(files);
        List<SamplePoint> dataPoints = getDataPoints(files, seriesName, epsgCode);
        if (dataPoints.isEmpty()) {
            return null;
        }

        Envelope envelope = Envelope.of(dataPoints, epsgCode);
        // add padding of blanking distance to envelope
        double padding = ((int) Math.ceil(params.blankingDistance() / params.cellSize()) + 1) * params.cellSize();
        envelope = envelope.expand(padding);

        double width = envelope.width();
        double height = envelope.height();

        int gridWidth = (int) (width / params.cellSize());
        int gridHeight = (int) (height / params.cellSize());
        if (gridWidth == 0 || gridHeight == 0) {
            return null;
        }

        Map<CellIndex, List<Double>> cellValues = new HashMap<>();
        for (SamplePoint point : dataPoints) {
            CellIndex cellIndex = envelope.cellIndex(point, gridWidth, gridHeight);
            cellValues.computeIfAbsent(cellIndex, (k -> new ArrayList<>()))
                    .add(point.value());
        }

        float[][] grid = new float[gridWidth][gridHeight];
        boolean[][] mask = new boolean[gridWidth][gridHeight];
        for (int i = 0; i < gridWidth; i++) {
            Arrays.fill(mask[i], true);
        }

        float median = (float) QuickSelect.getMedian(dataPoints, SamplePoint::value);

        for (Map.Entry<CellIndex, List<Double>> entry : cellValues.entrySet()) {
            CellIndex cellIndex = entry.getKey();
            int x = cellIndex.x();
            int y = cellIndex.y();
            // subtract global median from the value
            grid[x][y] = (float) calculateMedian(entry.getValue()) - median;
            mask[x][y] = false;
        }

        log.info("Filtering complete in {} ms", (int) ((System.nanoTime() - filteringStart) * 1e-6));

        if (Thread.currentThread().isInterrupted()) {
            log.info("Gridding interrupted");
            return null;
        }

        interpolateSplines(grid, mask);

        if (Thread.currentThread().isInterrupted()) {
            log.info("Gridding interrupted");
            return null;
        }

        // visibility mask
        int blankingRadius = (int) (params.blankingDistance() / params.cellSize());
        boolean[][] visiblePoints = new boolean[gridWidth][gridHeight];
        for (Map.Entry<CellIndex, List<Double>> entry : cellValues.entrySet()) {
            CellIndex cellIndex = entry.getKey();
            int x = cellIndex.x();
            int y = cellIndex.y();

            for (int dx = -blankingRadius; dx <= blankingRadius; dx++) {
                for (int dy = -blankingRadius; dy <= blankingRadius; dy++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx >= 0 && nx < gridWidth && ny >= 0 && ny < gridHeight) {
                        visiblePoints[nx][ny] = true;
                    }
                }
            }
        }

        for (int i = 0; i < gridWidth; i++) {
            for (int j = 0; j < gridHeight; j++) {
                if (!visiblePoints[i][j]) {
                    grid[i][j] = Float.NaN;
                } else {
                    grid[i][j] += median;
                }
            }
        }

        return new GriddingResult(
                seriesName,
                grid,
                new LatLon(mapEnvelope(files).coordinates().minY(), mapEnvelope(files).coordinates().minX()),
                new LatLon(mapEnvelope(files).coordinates().maxY(), mapEnvelope(files).coordinates().maxX()),
                params,
                envelope.coordinates()
        );
    }

    private void interpolateSplines(float[][] grid, boolean[][] mask) {
        log.info("Splines interpolation");
        long interpolationStart = System.nanoTime();

        SplinesGridder2 gridder = new SplinesGridder2();
        gridder.setMaxIterations(SPLINES_MAX_ITERATIONS);
        gridder.setTension(SPLINES_TENSION);
        gridder.gridMissing(mask, grid);

        log.info("Iterations: {}/{}, tension: {}, time: {} ms",
                gridder.getIterationCount(),
                SPLINES_MAX_ITERATIONS,
                SPLINES_TENSION,
                (int) ((System.nanoTime() - interpolationStart) * 1e-6));
        log.info("Interpolation complete");
    }

    private int getWorkingEpsg(Collection<SgyFile> files) {
        int epsgCode = -1;
        for (SgyFile file : files) {
            ColumnSchema schema = GeoData.getSchema(file.getGeoData());
            WorkingCoordinates working = schema != null ? schema.getWorkingCoordinates() : null;
            int fileEpsg = working != null && working.epsgCode() != 4326 ? working.epsgCode() : 3857;
            if (epsgCode != -1 && epsgCode != fileEpsg) {
                throw new IllegalArgumentException("All gridded files must use the same active working CRS");
            }
            epsgCode = fileEpsg;
        }
        return epsgCode != -1 ? epsgCode : 3857;
    }

    private List<SamplePoint> getDataPoints(Collection<SgyFile> files, String seriesName, int epsgCode) {
        List<SamplePoint> points = new ArrayList<>();
        for (SgyFile file : files) {
            points.addAll(getDataPoints(file, seriesName, epsgCode));
        }
        return points;
    }

    private List<SamplePoint> getDataPoints(SgyFile file, String seriesName, int epsgCode) {
        if (file == null) {
            return List.of();
        }

        List<GeoData> values = Nulls.toEmpty(file.getGeoData());
        ColumnSchema schema = GeoData.getSchema(values);
        if (schema == null) {
            return List.of();
        }

        int valueIndex = schema.getColumnIndex(seriesName);
        WorkingCoordinates working = schema.getWorkingCoordinates();
        int latitudeIndex = schema.getColumnIndex(schema.getHeaderBySemantic(Semantic.LATITUDE.getName()));
        int longitudeIndex = schema.getColumnIndex(schema.getHeaderBySemantic(Semantic.LONGITUDE.getName()));
        int xIndex = working != null ? schema.getColumnIndex(working.xSeries()) : -1;
        int yIndex = working != null ? schema.getColumnIndex(working.ySeries()) : -1;
        if (latitudeIndex == -1 || longitudeIndex == -1 || valueIndex == -1 || working != null && (xIndex == -1 || yIndex == -1)) {
            return List.of();
        }

        List<SamplePoint> points = new ArrayList<>(values.size());
        for (GeoData value : values) {
            Number latitude = value.getNumber(latitudeIndex);
            Number longitude = value.getNumber(longitudeIndex);
            Number pointValue = value.getNumber(valueIndex);
            if (latitude == null || longitude == null || pointValue == null) {
                continue;
            }
            double x;
            double y;
            if (working != null && working.epsgCode() != 4326) {
                Number workingX = value.getNumber(xIndex);
                Number workingY = value.getNumber(yIndex);
                if (workingX == null || workingY == null) continue;
                x = workingX.doubleValue();
                y = workingY.doubleValue();
            } else {
                var point = SphericalMercator.project(latitude.doubleValue(), longitude.doubleValue());
                x = point.getX();
                y = point.getY();
            }
            points.add(new SamplePoint(x, y, pointValue.doubleValue()));
        }
        return points;
    }

    private static double calculateMedian(List<Double> values) {
        return QuickSelect.getMedian(values, Double::doubleValue);
    }

    private static Envelope mapEnvelope(Collection<SgyFile> files) {
        List<LatLon> points = new ArrayList<>();
        for (SgyFile file : files) {
            for (GeoData value : Nulls.toEmpty(file.getGeoData())) {
                LatLon point = value.getLatLon();
                if (point != null) points.add(point);
            }
        }
        return Envelope.ofMap(points);
    }

    private record Envelope(GridCoordinates coordinates) {

        public double width() {
            return coordinates.width();
        }

        public double height() {
            return coordinates.height();
        }

        public CellIndex cellIndex(SamplePoint point, int gridWidth, int gridHeight) {
            int x = (int) (gridWidth * (point.x() - coordinates.minX()) / coordinates.width());
            int y = (int) (gridHeight * (point.y() - coordinates.minY()) / coordinates.height());
            return new CellIndex(
                    Math.clamp(x, 0, gridWidth - 1),
                    Math.clamp(y, 0, gridHeight - 1));
        }

        public Envelope expand(double distance) {
            Check.condition(distance >= 0);

            return new Envelope(coordinates.expand(distance));
        }
        
        public static Envelope of(List<SamplePoint> points, int epsgCode) {
            Check.notEmpty(points);

            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (SamplePoint point : points) {
                minX = Math.min(minX, point.x()); minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x()); maxY = Math.max(maxY, point.y());
            }
            return new Envelope(new GridCoordinates(minX, minY, maxX, maxY, epsgCode));
        }

        public static Envelope ofMap(List<LatLon> points) {
            double minLat = Double.POSITIVE_INFINITY, minLon = Double.POSITIVE_INFINITY;
            double maxLat = Double.NEGATIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
            for (LatLon point : points) {
                minLat = Math.min(minLat, point.getLatDgr()); minLon = Math.min(minLon, point.getLonDgr());
                maxLat = Math.max(maxLat, point.getLatDgr()); maxLon = Math.max(maxLon, point.getLonDgr());
            }
            return new Envelope(new GridCoordinates(minLon, minLat, maxLon, maxLat, 4326));
        }
    }

    private record CellIndex(int x, int y) {}

    private record SamplePoint(double x, double y, double value) {}
}
