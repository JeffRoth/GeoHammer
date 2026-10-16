package com.ugcs.geohammer.service.quality;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.CoordinatePair;
import com.ugcs.geohammer.model.LatLon;
import com.ugcs.geohammer.model.Semantic;
import com.ugcs.geohammer.service.coordinates.CoordinatePoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class SurveyGeometryService {

    private static final double ORIENTATION_TOLERANCE = 20.0;

    private static final double METERS_PER_DEGREE = 111_320.0;

    private static final double DEFAULT_OFFSET_BIN_WIDTH = 100.0;

    private static final double TIE_ORIENTATION_SEARCH_RANGE = 20.0;

    private static final double TIE_ORIENTATION_SEARCH_STEP = 0.5;

    public SurveyGeometryReport analyze(List<GeoData> data) {
        List<LineGeometry> lines = collectLines(data, activeCoordinates(data));
        List<Double> sampleSpacing = new ArrayList<>();
        List<Double> headings = new ArrayList<>();
        for (LineGeometry line : lines) {
            collectSegments(line.points(), sampleSpacing, headings);
        }

        LineFamilies families = splitLineFamilies(lines);
        List<Double> primaryOffsets = collectLineOffsets(families.primary());
        List<Double> primaryLineSpacing = collectAdjacentSpacing(primaryOffsets);
        SurveyGeometryReport.Distribution sampleSummary = summarize(sampleSpacing);
        SurveyGeometryReport.Distribution primarySummary = summarize(primaryLineSpacing);
        TieLinePeaks tiePeaks = findTieLinePeaks(families.tieLines(), primarySummary.median(),
                families.primary().orientation());

        double cellSize = recommendCellSize(primarySummary);
        double blankingDistance = recommendBlankingDistance(primarySummary);
        return new SurveyGeometryReport(lines.size(), families.primary().lines().size(), families.tieLines().lines().size(),
                families.primary().orientation(), tiePeaks.orientation(), toIds(families.primary()),
                toIds(families.tieLines()), toPaths(families.primary()),
                toPaths(families.tieLines()), sampleSummary, primarySummary, summarize(headings),
                List.copyOf(sampleSpacing), List.copyOf(primaryLineSpacing), List.copyOf(headings),
                tiePeaks.spacing(), tiePeaks.peakPositions().size(), tiePeaks.densityPositions(),
                tiePeaks.densityValues(), tiePeaks.peakPositions(),
                cellSize, blankingDistance);
    }

    private static List<List<LatLon>> toPaths(LineFamily family) {
        List<List<LatLon>> paths = new ArrayList<>(family.lines().size());
        for (LineGeometry line : family.lines()) {
            if (line.mapPath().size() > 1) {
                paths.add(List.copyOf(line.mapPath()));
            }
        }
        return List.copyOf(paths);
    }

    private static List<Integer> toIds(LineFamily family) {
        List<Integer> ids = new ArrayList<>();
        for (LineGeometry line : family.lines()) {
            if (!ids.contains(line.id())) {
                ids.add(line.id());
            }
        }
        return List.copyOf(ids);
    }

    private List<LineGeometry> collectLines(List<GeoData> data, CoordinatePair pair) {
        List<LineGeometry> lines = new ArrayList<>();
        List<SpatialPoint> points = new ArrayList<>();
        Integer currentLine = null;
        LatLon geographicOrigin = pair.epsgCode() == 4326
                ? geographicOrigin(data, pair)
                : null;
        for (GeoData value : data) {
            if (value == null) {
                continue;
            }
            Integer line = value.getLineOrDefault(0);
            if (currentLine != null && !line.equals(currentLine)) {
                addLine(lines, currentLine, points);
                points = new ArrayList<>();
            }
            currentLine = line;
            SpatialPoint point = spatialPoint(value, pair, geographicOrigin);
            if (point != null) {
                points.add(point);
            }
        }
        if (currentLine != null) {
            addLine(lines, currentLine, points);
        }
        return lines;
    }

    private CoordinatePair activeCoordinates(List<GeoData> data) {
        ColumnSchema schema = GeoData.getSchema(data);
        if (schema != null && schema.getSpatialReference() != null && schema.getSpatialReference().activePair() != null) {
            return schema.getSpatialReference().activePair();
        }
        String longitude = schema != null ? schema.getHeaderBySemantic(Semantic.LONGITUDE.getName()) : null;
        String latitude = schema != null ? schema.getHeaderBySemantic(Semantic.LATITUDE.getName()) : null;
        if (longitude == null || latitude == null) {
            throw new IllegalArgumentException("Select working coordinates before analyzing survey geometry");
        }
        return new CoordinatePair(longitude, latitude, 4326);
    }

    private static LatLon geographicOrigin(List<GeoData> data, CoordinatePair pair) {
        for (GeoData value : data) {
            Number longitude = value.getNumber(pair.xSeries());
            Number latitude = value.getNumber(pair.ySeries());
            if (longitude != null && latitude != null) {
                return new LatLon(latitude.doubleValue(), longitude.doubleValue());
            }
        }
        return null;
    }

    private SpatialPoint spatialPoint(GeoData value, CoordinatePair pair, LatLon geographicOrigin) {
        Number x = value.getNumber(pair.xSeries());
        Number y = value.getNumber(pair.ySeries());
        if (x == null || y == null) {
            return null;
        }
        CoordinatePoint point;
        if (pair.epsgCode() == 4326) {
            if (geographicOrigin == null) {
                return null;
            }
            point = new CoordinatePoint((x.doubleValue() - geographicOrigin.getLonDgr()) * METERS_PER_DEGREE
                    * Math.cos(Math.toRadians(geographicOrigin.getLatDgr())),
                    (y.doubleValue() - geographicOrigin.getLatDgr()) * METERS_PER_DEGREE);
        } else {
            point = new CoordinatePoint(x.doubleValue(), y.doubleValue());
        }
        return new SpatialPoint(point.x(), point.y(), value.getLatLon());
    }

    private static void addLine(List<LineGeometry> lines, int id, List<SpatialPoint> points) {
        if (points.size() < 2) {
            return;
        }
        double x = 0.0;
        double y = 0.0;
        List<LatLon> mapPath = new ArrayList<>(points.size());
        for (SpatialPoint point : points) {
            x += point.x();
            y += point.y();
            if (point.mapLocation() != null) {
                mapPath.add(point.mapLocation());
            }
        }
        SpatialPoint first = points.getFirst();
        SpatialPoint last = points.getLast();
        double orientation = heading(first, last) % 180.0;
        lines.add(new LineGeometry(id, points, new CoordinatePoint(x / points.size(), y / points.size()), orientation, mapPath));
    }

    private static void collectSegments(List<SpatialPoint> points, List<Double> sampleSpacing, List<Double> headings) {
        for (int i = 1; i < points.size(); i++) {
            SpatialPoint previous = points.get(i - 1);
            SpatialPoint current = points.get(i);
            double distance = Math.hypot(current.x() - previous.x(), current.y() - previous.y());
            if (!Double.isFinite(distance) || distance <= 0.0) {
                continue;
            }
            sampleSpacing.add(distance);
            headings.add(heading(previous, current));
        }
    }

    private static LineFamilies splitLineFamilies(List<LineGeometry> lines) {
        if (lines.isEmpty()) {
            return new LineFamilies(new LineFamily(List.of(), Double.NaN), new LineFamily(List.of(), Double.NaN));
        }
        LineGeometry dominant = lines.getFirst();
        int largestGroup = 0;
        for (LineGeometry candidate : lines) {
            int count = 0;
            for (LineGeometry line : lines) {
                if (orientationDifference(candidate.orientation(), line.orientation()) <= ORIENTATION_TOLERANCE) {
                    count++;
                }
            }
            if (count > largestGroup) {
                dominant = candidate;
                largestGroup = count;
            }
        }

        List<LineGeometry> primary = new ArrayList<>();
        List<LineGeometry> ties = new ArrayList<>();
        for (LineGeometry line : lines) {
            if (orientationDifference(dominant.orientation(), line.orientation()) <= ORIENTATION_TOLERANCE) {
                primary.add(line);
            } else {
                ties.add(line);
            }
        }
        return new LineFamilies(new LineFamily(primary, meanOrientation(primary)), new LineFamily(ties, meanOrientation(ties)));
    }

    private static double orientationDifference(double first, double second) {
        double difference = Math.abs(first - second);
        return Math.min(difference, 180.0 - difference);
    }

    private static double meanOrientation(List<LineGeometry> lines) {
        if (lines.isEmpty()) {
            return Double.NaN;
        }
        double x = 0.0;
        double y = 0.0;
        for (LineGeometry line : lines) {
            double angle = Math.toRadians(2.0 * line.orientation());
            x += Math.cos(angle);
            y += Math.sin(angle);
        }
        double orientation = Math.toDegrees(Math.atan2(y, x)) / 2.0;
        return orientation < 0.0 ? orientation + 180.0 : orientation;
    }

    private static List<Double> collectLineOffsets(LineFamily family) {
        List<Double> offsets = new ArrayList<>();
        if (family.lines().size() < 2 || !Double.isFinite(family.orientation())) {
            return offsets;
        }
        CoordinatePoint origin = family.lines().getFirst().center();
        double orientation = Math.toRadians(family.orientation());
        for (LineGeometry line : family.lines()) {
            List<Double> lineOffsets = new ArrayList<>(line.points().size());
            for (SpatialPoint point : line.points()) {
                lineOffsets.add(projectOffset(point, origin, orientation));
            }
            offsets.add(median(lineOffsets));
        }
        offsets.sort(Comparator.naturalOrder());
        return offsets;
    }

    private static List<Double> collectAdjacentSpacing(List<Double> offsets) {
        List<Double> spacing = new ArrayList<>();
        for (int i = 1; i < offsets.size(); i++) {
            double distance = offsets.get(i) - offsets.get(i - 1);
            if (distance > 0.0) {
                spacing.add(distance);
            }
        }
        return spacing;
    }

    private static TieLinePeaks findTieLinePeaks(LineFamily tieLines, double primaryLineSpacing,
                                                 double primaryOrientation) {
        if (tieLines.lines().isEmpty()) {
            return TieLinePeaks.empty();
        }
        double orientation = findDominantTieOrientation(tieLines, primaryLineSpacing, primaryOrientation);
        List<Double> offsets = collectSampleOffsets(tieLines, orientation);
        if (offsets.size() < 2) {
            return new TieLinePeaks(orientation, Double.NaN, List.of(), List.of(), List.of());
        }
        double binWidth = Double.isFinite(primaryLineSpacing)
                ? Math.max(20.0, primaryLineSpacing / 4.0)
                : DEFAULT_OFFSET_BIN_WIDTH;
        double minimum = offsets.getFirst();
        double maximum = offsets.getLast();
        double origin = Math.floor(minimum / binWidth) * binWidth;
        int binCount = (int) Math.ceil((maximum - origin) / binWidth) + 1;
        int[] counts = new int[binCount];
        for (double offset : offsets) {
            int index = Math.min((int) ((offset - origin) / binWidth), binCount - 1);
            counts[Math.max(index, 0)]++;
        }

        int radius = Math.max(1, (int) Math.round(primaryLineSpacing / (2.0 * binWidth)));
        List<Double> densityPositions = new ArrayList<>(binCount);
        List<Double> densityValues = new ArrayList<>(binCount);
        double maximumDensity = 0.0;
        for (int i = 0; i < binCount; i++) {
            int sum = 0;
            for (int j = Math.max(0, i - radius); j <= Math.min(binCount - 1, i + radius); j++) {
                sum += counts[j];
            }
            double density = (double) sum / (2 * radius + 1);
            densityPositions.add(origin + (i + 0.5) * binWidth);
            densityValues.add(density);
            maximumDensity = Math.max(maximumDensity, density);
        }
        List<Double> peaks = findDensityPeaks(densityPositions, densityValues, maximumDensity, primaryLineSpacing,
                binWidth);
        List<Double> peakSpacing = collectAdjacentSpacing(peaks);
        double spacing = peakSpacing.isEmpty() ? Double.NaN : median(peakSpacing);
        return new TieLinePeaks(orientation, spacing, List.copyOf(densityPositions),
                List.copyOf(densityValues), List.copyOf(peaks));
    }

    private static double findDominantTieOrientation(LineFamily tieLines, double primaryLineSpacing,
                                                     double primaryOrientation) {
        double expected = Double.isFinite(primaryOrientation)
                ? normalizeOrientation(primaryOrientation + 90.0)
                : tieLines.orientation();
        if (!Double.isFinite(expected)) {
            return Double.NaN;
        }
        double binWidth = Double.isFinite(primaryLineSpacing)
                ? Math.max(20.0, primaryLineSpacing / 4.0)
                : DEFAULT_OFFSET_BIN_WIDTH;
        double bestOrientation = expected;
        long bestScore = Long.MIN_VALUE;
        for (double offset = -TIE_ORIENTATION_SEARCH_RANGE; offset <= TIE_ORIENTATION_SEARCH_RANGE;
             offset += TIE_ORIENTATION_SEARCH_STEP) {
            double candidate = normalizeOrientation(expected + offset);
            long score = calculateOrientationScore(tieLines, candidate, binWidth);
            if (score > bestScore) {
                bestScore = score;
                bestOrientation = candidate;
            }
        }
        return bestOrientation;
    }

    private static long calculateOrientationScore(LineFamily family, double orientation, double binWidth) {
        CoordinatePoint origin = family.lines().getFirst().center();
        double radians = Math.toRadians(orientation);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (LineGeometry line : family.lines()) {
            for (SpatialPoint point : line.points()) {
                double offset = projectOffset(point, origin, radians);
                minimum = Math.min(minimum, offset);
                maximum = Math.max(maximum, offset);
            }
        }
        int binCount = (int) Math.ceil((maximum - minimum) / binWidth) + 1;
        int[] counts = new int[binCount];
        for (LineGeometry line : family.lines()) {
            for (SpatialPoint point : line.points()) {
                double offset = projectOffset(point, origin, radians);
                int index = Math.min((int) ((offset - minimum) / binWidth), binCount - 1);
                counts[Math.max(index, 0)]++;
            }
        }
        long score = 0;
        for (int count : counts) {
            score += (long) count * count;
        }
        return score;
    }

    private static List<Double> collectSampleOffsets(LineFamily family, double orientation) {
        List<Double> offsets = new ArrayList<>();
        if (family.lines().isEmpty() || !Double.isFinite(orientation)) {
            return offsets;
        }
        CoordinatePoint origin = family.lines().getFirst().center();
        double radians = Math.toRadians(orientation);
        for (LineGeometry line : family.lines()) {
            for (SpatialPoint point : line.points()) {
                offsets.add(projectOffset(point, origin, radians));
            }
        }
        offsets.sort(Comparator.naturalOrder());
        return offsets;
    }

    private static double projectOffset(SpatialPoint point, CoordinatePoint origin, double orientation) {
        double east = point.x() - origin.x();
        double north = point.y() - origin.y();
        return east * Math.cos(orientation) - north * Math.sin(orientation);
    }

    private static double normalizeOrientation(double orientation) {
        double normalized = orientation % 180.0;
        return normalized < 0.0 ? normalized + 180.0 : normalized;
    }

    private static List<Double> findDensityPeaks(List<Double> positions, List<Double> densityValues,
                                                  double maximumDensity, double primaryLineSpacing,
                                                  double binWidth) {
        List<Peak> candidates = new ArrayList<>();
        double threshold = maximumDensity * 0.1;
        for (int i = 0; i < densityValues.size(); i++) {
            double value = densityValues.get(i);
            double previous = i > 0 ? densityValues.get(i - 1) : Double.NEGATIVE_INFINITY;
            double next = i + 1 < densityValues.size() ? densityValues.get(i + 1) : Double.NEGATIVE_INFINITY;
            if (value >= threshold && value >= previous && value > next) {
                candidates.add(new Peak(positions.get(i), value));
            }
        }
        candidates.sort((first, second) -> Double.compare(second.density(), first.density()));

        double minimumPeakDistance = Double.isFinite(primaryLineSpacing)
                ? primaryLineSpacing * 3.0
                : binWidth * 4.0;
        List<Double> peaks = new ArrayList<>();
        for (Peak candidate : candidates) {
            boolean separated = true;
            for (double peak : peaks) {
                if (Math.abs(candidate.position() - peak) < minimumPeakDistance) {
                    separated = false;
                    break;
                }
            }
            if (separated) {
                peaks.add(candidate.position());
            }
        }
        peaks.sort(Comparator.naturalOrder());
        return peaks;
    }

    private static double median(List<Double> values) {
        values.sort(Comparator.naturalOrder());
        return percentile(values, 0.5);
    }

    private static SurveyGeometryReport.Distribution summarize(List<Double> values) {
        if (values.isEmpty()) {
            return new SurveyGeometryReport.Distribution(0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN);
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return new SurveyGeometryReport.Distribution(sorted.size(), sorted.getFirst(), percentile(sorted, 0.25),
                percentile(sorted, 0.5), percentile(sorted, 0.75), sorted.getLast());
    }

    private static double percentile(List<Double> sorted, double percentile) {
        double index = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (index - lower);
    }

    private static double recommendCellSize(SurveyGeometryReport.Distribution primaryLineSpacing) {
        if (primaryLineSpacing.isEmpty()) {
            return Double.NaN;
        }
        return primaryLineSpacing.median() / 4.0;
    }

    private static double recommendBlankingDistance(SurveyGeometryReport.Distribution primaryLineSpacing) {
        if (primaryLineSpacing.isEmpty()) {
            return Double.NaN;
        }
        return primaryLineSpacing.median() * 2.0;
    }

    private static double heading(SpatialPoint from, SpatialPoint to) {
        return normalizeHeading(Math.toDegrees(Math.atan2(to.x() - from.x(), to.y() - from.y())));
    }

    private static double normalizeHeading(double heading) {
        double normalized = heading % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private record SpatialPoint(double x, double y, @org.jspecify.annotations.Nullable LatLon mapLocation) {
    }

    private record LineGeometry(int id, List<SpatialPoint> points, CoordinatePoint center, double orientation,
                                List<LatLon> mapPath) {
    }

    private record LineFamily(List<LineGeometry> lines, double orientation) {
    }

    private record LineFamilies(LineFamily primary, LineFamily tieLines) {
    }

    private record Peak(double position, double density) {
    }

    private record TieLinePeaks(double orientation, double spacing, List<Double> densityPositions,
                                List<Double> densityValues,
                                List<Double> peakPositions) {

        private static TieLinePeaks empty() {
            return new TieLinePeaks(Double.NaN, Double.NaN, List.of(), List.of(), List.of());
        }
    }
}
