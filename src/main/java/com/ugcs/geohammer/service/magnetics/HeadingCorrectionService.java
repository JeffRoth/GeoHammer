package com.ugcs.geohammer.service.magnetics;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.LatLon;
import com.ugcs.geohammer.util.Check;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class HeadingCorrectionService {

    private static final double CELL_SIZE_METERS = 100;

    private static final double METERS_PER_DEGREE = 111_320;

    public HeadingCorrectionResult correct(List<GeoData> data, String series, int headingBins) {
        Check.notEmpty(data);
        Check.notEmpty(series);
        Check.condition(headingBins >= 4 && headingBins <= 36, "Heading correction requires between 4 and 36 bins");

        Map<Integer, List<Sample>> lines = collectLines(data, series);
        List<HeadingCrossover> crossovers = findCrossovers(lines, headingBins);
        Check.condition(!crossovers.isEmpty(), "No line crossovers with different headings were found");

        Map<Integer, Double> corrections = solveCorrections(crossovers);
        double[] correctionByIndex = new double[data.size()];
        for (List<Sample> line : lines.values()) {
            for (int index = 0; index < line.size(); index++) {
                int next = Math.min(index + 1, line.size() - 1);
                int previous = Math.max(index - 1, 0);
                Sample sample = line.get(index);
                int bin = headingBin(line.get(previous), line.get(next), headingBins);
                correctionByIndex[sample.dataIndex] = corrections.getOrDefault(bin, 0.0);
            }
        }

        List<@Nullable Number> corrected = new ArrayList<>(data.size());
        for (int index = 0; index < data.size(); index++) {
            Number field = data.get(index).getNumber(series);
            corrected.add(field == null || !Double.isFinite(field.doubleValue())
                    ? null
                    : field.doubleValue() - correctionByIndex[index]);
        }
        return new HeadingCorrectionResult(corrected, crossovers, corrections, rmsError(crossovers, corrections));
    }

    private static Map<Integer, List<Sample>> collectLines(List<GeoData> data, String series) {
        Map<Integer, List<Sample>> lines = new LinkedHashMap<>();
        for (int index = 0; index < data.size(); index++) {
            GeoData value = data.get(index);
            LatLon location = value.getLatLon();
            Number field = value.getNumber(series);
            if (location == null || field == null || !Double.isFinite(field.doubleValue())) {
                continue;
            }
            lines.computeIfAbsent(value.getLineOrDefault(0), ignored -> new ArrayList<>())
                    .add(new Sample(index, location.getLatDgr(), location.getLonDgr(), field.doubleValue()));
        }
        lines.values().removeIf(line -> line.size() < 2);
        Check.condition(lines.size() > 1, "Heading correction requires at least two lines with coordinates and values");
        return lines;
    }

    private static List<HeadingCrossover> findCrossovers(Map<Integer, List<Sample>> lines, int headingBins) {
        List<HeadingCrossover> crossovers = new ArrayList<>();
        double referenceLatitude = averageLatitude(lines);
        Map<GridCell, List<Segment>> index = new HashMap<>();
        for (Map.Entry<Integer, List<Sample>> entry : lines.entrySet()) {
            List<Sample> line = entry.getValue();
            for (int sampleIndex = 1; sampleIndex < line.size(); sampleIndex++) {
                Segment segment = new Segment(entry.getKey(), line.get(sampleIndex - 1), line.get(sampleIndex));
                indexSegment(index, segment, referenceLatitude);
            }
        }
        for (Map.Entry<GridCell, List<Segment>> entry : index.entrySet()) {
            findCellCrossovers(entry.getKey(), entry.getValue(), headingBins, referenceLatitude, crossovers);
        }
        return crossovers;
    }

    private static double averageLatitude(Map<Integer, List<Sample>> lines) {
        double sum = 0;
        int count = 0;
        for (List<Sample> line : lines.values()) {
            for (Sample sample : line) {
                sum += sample.latitude;
                count++;
            }
        }
        return sum / count;
    }

    private static void indexSegment(Map<GridCell, List<Segment>> index, Segment segment, double referenceLatitude) {
        GridCell first = cellOf(segment.first, referenceLatitude);
        GridCell second = cellOf(segment.second, referenceLatitude);
        int minX = Math.min(first.x, second.x);
        int maxX = Math.max(first.x, second.x);
        int minY = Math.min(first.y, second.y);
        int maxY = Math.max(first.y, second.y);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                index.computeIfAbsent(new GridCell(x, y), ignored -> new ArrayList<>()).add(segment);
            }
        }
    }

    private static void findCellCrossovers(GridCell cell, List<Segment> segments, int headingBins,
                                           double referenceLatitude, List<HeadingCrossover> crossovers) {
        for (int firstIndex = 0; firstIndex < segments.size(); firstIndex++) {
            Segment first = segments.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < segments.size(); secondIndex++) {
                Segment second = segments.get(secondIndex);
                if (first.line == second.line) {
                    continue;
                }
                Intersection intersection = intersect(first, second, headingBins);
                if (intersection != null && cellOf(intersection.latitude, intersection.longitude, referenceLatitude).equals(cell)
                        && intersection.crossover.firstHeadingBin() != intersection.crossover.secondHeadingBin()) {
                    crossovers.add(intersection.crossover);
                }
            }
        }
    }

    private static @Nullable Intersection intersect(Segment first, Segment second, int headingBins) {
        Sample a = first.first;
        Sample b = first.second;
        Sample c = second.first;
        Sample d = second.second;
        double denominator = (b.longitude - a.longitude) * (d.latitude - c.latitude)
                - (b.latitude - a.latitude) * (d.longitude - c.longitude);
        if (Math.abs(denominator) < 1e-12) {
            return null;
        }
        double t = ((c.longitude - a.longitude) * (d.latitude - c.latitude)
                - (c.latitude - a.latitude) * (d.longitude - c.longitude)) / denominator;
        double u = ((c.longitude - a.longitude) * (b.latitude - a.latitude)
                - (c.latitude - a.latitude) * (b.longitude - a.longitude)) / denominator;
        if (t < 0 || t > 1 || u < 0 || u > 1) {
            return null;
        }
        double firstField = a.field + t * (b.field - a.field);
        double secondField = c.field + u * (d.field - c.field);
        HeadingCrossover crossover = new HeadingCrossover(first.line, second.line, headingBin(a, b, headingBins),
                headingBin(c, d, headingBins), firstField - secondField);
        double latitude = a.latitude + t * (b.latitude - a.latitude);
        double longitude = a.longitude + t * (b.longitude - a.longitude);
        return new Intersection(crossover, latitude, longitude);
    }

    private static GridCell cellOf(Sample sample, double referenceLatitude) {
        return cellOf(sample.latitude, sample.longitude, referenceLatitude);
    }

    private static GridCell cellOf(double latitude, double longitude, double referenceLatitude) {
        double x = longitude * METERS_PER_DEGREE * Math.cos(Math.toRadians(referenceLatitude));
        double y = latitude * METERS_PER_DEGREE;
        return new GridCell((int) Math.floor(x / CELL_SIZE_METERS), (int) Math.floor(y / CELL_SIZE_METERS));
    }

    private static Map<Integer, Double> solveCorrections(List<HeadingCrossover> crossovers) {
        Set<Integer> activeBins = new LinkedHashSet<>();
        for (HeadingCrossover crossover : crossovers) {
            activeBins.add(crossover.firstHeadingBin());
            activeBins.add(crossover.secondHeadingBin());
        }
        List<Integer> bins = new ArrayList<>(activeBins);
        Check.condition(bins.size() > 1, "Heading correction requires crossovers between different heading bins");

        int[] variableByBin = new int[37];
        Arrays.fill(variableByBin, -1);
        for (int index = 1; index < bins.size(); index++) {
            variableByBin[bins.get(index)] = index - 1;
        }
        double[][] normal = new double[bins.size() - 1][bins.size() - 1];
        double[] rightHandSide = new double[bins.size() - 1];
        for (HeadingCrossover crossover : crossovers) {
            addEquation(normal, rightHandSide, variableByBin[crossover.firstHeadingBin()],
                    variableByBin[crossover.secondHeadingBin()], crossover.error());
        }
        double[] solution = solve(normal, rightHandSide);
        Map<Integer, Double> corrections = new LinkedHashMap<>();
        corrections.put(bins.getFirst(), 0.0);
        for (int index = 1; index < bins.size(); index++) {
            corrections.put(bins.get(index), solution[index - 1]);
        }
        return corrections;
    }

    private static void addEquation(double[][] normal, double[] rightHandSide, int firstVariable, int secondVariable,
                                    double value) {
        if (firstVariable >= 0) {
            normal[firstVariable][firstVariable]++;
            rightHandSide[firstVariable] += value;
        }
        if (secondVariable >= 0) {
            normal[secondVariable][secondVariable]++;
            rightHandSide[secondVariable] -= value;
        }
        if (firstVariable >= 0 && secondVariable >= 0) {
            normal[firstVariable][secondVariable]--;
            normal[secondVariable][firstVariable]--;
        }
    }

    private static double[] solve(double[][] matrix, double[] vector) {
        int size = vector.length;
        for (int pivot = 0; pivot < size; pivot++) {
            int largest = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[largest][pivot])) {
                    largest = row;
                }
            }
            Check.condition(Math.abs(matrix[largest][pivot]) > 1e-12,
                    "Heading bins are not connected by crossover observations");
            swap(matrix, pivot, largest);
            double temporary = vector[pivot];
            vector[pivot] = vector[largest];
            vector[largest] = temporary;

            double divisor = matrix[pivot][pivot];
            for (int column = pivot; column < size; column++) {
                matrix[pivot][column] /= divisor;
            }
            vector[pivot] /= divisor;
            for (int row = 0; row < size; row++) {
                if (row == pivot) {
                    continue;
                }
                double factor = matrix[row][pivot];
                for (int column = pivot; column < size; column++) {
                    matrix[row][column] -= factor * matrix[pivot][column];
                }
                vector[row] -= factor * vector[pivot];
            }
        }
        return vector;
    }

    private static void swap(double[][] matrix, int first, int second) {
        if (first == second) {
            return;
        }
        double[] temporary = matrix[first];
        matrix[first] = matrix[second];
        matrix[second] = temporary;
    }

    private static int headingBin(Sample start, Sample end, int headingBins) {
        double longitude = (end.longitude - start.longitude) * Math.cos(Math.toRadians((start.latitude + end.latitude) / 2));
        double latitude = end.latitude - start.latitude;
        double heading = Math.toDegrees(Math.atan2(longitude, latitude));
        double normalizedHeading = heading < 0 ? heading + 360 : heading;
        return Math.min((int) (normalizedHeading / 360 * headingBins), headingBins - 1);
    }

    private static double rmsError(List<HeadingCrossover> crossovers, Map<Integer, Double> corrections) {
        double sumSquares = 0;
        for (HeadingCrossover crossover : crossovers) {
            double residual = crossover.error() - corrections.get(crossover.firstHeadingBin())
                    + corrections.get(crossover.secondHeadingBin());
            sumSquares += residual * residual;
        }
        return Math.sqrt(sumSquares / crossovers.size());
    }

    private record Sample(int dataIndex, double latitude, double longitude, double field) {
    }

    private record Segment(int line, Sample first, Sample second) {
    }

    private record GridCell(int x, int y) {
    }

    private record Intersection(HeadingCrossover crossover, double latitude, double longitude) {
    }
}
