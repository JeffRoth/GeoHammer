package com.ugcs.geohammer.service.magnetics;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.service.coordinates.CoordinateReferenceService;
import com.ugcs.geohammer.util.Check;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GnssLagEstimationService {

    private static final int MAXIMUM_SHIFT = 30;

    private static final double RECIPROCAL_ALIGNMENT = Math.cos(Math.toRadians(30));

    private final CoordinateReferenceService coordinates;

    public GnssLagEstimationService(CoordinateReferenceService coordinates) {
        this.coordinates = coordinates;
    }

    public GnssLagEstimate estimate(List<GeoData> data, String series) {
        SpatialSurveyCoordinates spatial = SpatialSurveyCoordinates.of(data, coordinates);
        List<Line> lines = collectLines(data, series, spatial);
        Check.condition(lines.size() >= 2, "GNSS lag estimation requires at least two lines with coordinates and values");
        List<PairEstimate> estimates = new ArrayList<>();
        for (int first = 0; first < lines.size(); first++) {
            Line firstLine = lines.get(first);
            Line nearest = nearestReciprocalLine(firstLine, lines);
            if (nearest == null) {
                continue;
            }
            PairEstimate estimate = estimatePair(firstLine, nearest);
            if (estimate.correlation >= 0.5) {
                estimates.add(estimate);
            }
        }
        Check.condition(!estimates.isEmpty(), "No correlated reciprocal line pairs were found; select a magnetic series "
                + "with coherent anomalies and ensure both flight directions are present");
        estimates.sort(Comparator.comparingInt(PairEstimate::shift));
        int shift = estimates.get(estimates.size() / 2).shift;
        double correlation = 0.0;
        double distance = 0.0;
        for (PairEstimate estimate : estimates) {
            correlation += estimate.correlation;
            distance += estimate.sampleSpacing;
        }
        return new GnssLagEstimate(shift, correlation / estimates.size(), estimates.size(),
                Math.abs(shift) * distance / estimates.size());
    }

    private static List<Line> collectLines(List<GeoData> data, String series, SpatialSurveyCoordinates spatial) {
        Map<Integer, List<Sample>> samplesByLine = new LinkedHashMap<>();
        for (GeoData value : data) {
            Number field = value.getNumber(series);
            SpatialSurveyCoordinates.SpatialPoint point = spatial.point(value);
            if (field == null || point == null || !Double.isFinite(field.doubleValue())) {
                continue;
            }
            if (spatial.isGeographic()) {
                point = spatial.transform(point, 3857);
            }
            samplesByLine.computeIfAbsent(value.getLineOrDefault(0), ignored -> new ArrayList<>())
                    .add(new Sample(point.x(), point.y(), field.doubleValue()));
        }
        List<Line> lines = new ArrayList<>();
        for (List<Sample> samples : samplesByLine.values()) {
            if (samples.size() < 16) {
                continue;
            }
            Sample first = samples.getFirst();
            Sample last = samples.getLast();
            double dx = last.x - first.x;
            double dy = last.y - first.y;
            double length = Math.hypot(dx, dy);
            if (length > 0.0) {
                lines.add(new Line(samples, first.x + dx / 2.0, first.y + dy / 2.0, dx / length, dy / length,
                        length / (samples.size() - 1)));
            }
        }
        return lines;
    }

    private static Line nearestReciprocalLine(Line reference, List<Line> lines) {
        Line nearest = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Line candidate : lines) {
            if (candidate == reference || reference.xDirection * candidate.xDirection + reference.yDirection * candidate.yDirection
                    > -RECIPROCAL_ALIGNMENT) {
                continue;
            }
            double distance = Math.hypot(candidate.centerX - reference.centerX, candidate.centerY - reference.centerY);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static PairEstimate estimatePair(Line first, Line second) {
        int maximumShift = Math.min(MAXIMUM_SHIFT, Math.min(first.samples.size(), second.samples.size()) / 4);
        double bestCorrelation = Double.NEGATIVE_INFINITY;
        int bestShift = 0;
        for (int shift = -maximumShift; shift <= maximumShift; shift++) {
            double correlation = correlation(first.samples, second.samples, shift);
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestShift = shift;
            }
        }
        return new PairEstimate(bestShift, bestCorrelation, 0.5 * (first.sampleSpacing + second.sampleSpacing));
    }

    private static double correlation(List<Sample> first, List<Sample> reverseSecond, int shift) {
        int count = Math.min(first.size(), reverseSecond.size()) - Math.abs(shift);
        if (count < 12) {
            return Double.NEGATIVE_INFINITY;
        }
        int firstStart = Math.max(0, shift);
        int secondStart = Math.max(0, -shift);
        double firstMean = 0.0;
        double secondMean = 0.0;
        double positionMean = (count - 1) / 2.0;
        double positionPower = 0.0;
        double firstPositionCovariance = 0.0;
        double secondPositionCovariance = 0.0;
        for (int index = 0; index < count; index++) {
            firstMean += first.get(firstStart + index).value;
            secondMean += reverseSecond.get(reverseSecond.size() - 1 - secondStart - index).value;
        }
        firstMean /= count;
        secondMean /= count;
        for (int index = 0; index < count; index++) {
            double position = index - positionMean;
            positionPower += position * position;
            firstPositionCovariance += position * (first.get(firstStart + index).value - firstMean);
            secondPositionCovariance += position
                    * (reverseSecond.get(reverseSecond.size() - 1 - secondStart - index).value - secondMean);
        }
        double firstSlope = firstPositionCovariance / positionPower;
        double secondSlope = secondPositionCovariance / positionPower;
        double cross = 0.0;
        double firstPower = 0.0;
        double secondPower = 0.0;
        for (int index = 0; index < count; index++) {
            double position = index - positionMean;
            double firstValue = first.get(firstStart + index).value - firstMean - firstSlope * position;
            double secondValue = reverseSecond.get(reverseSecond.size() - 1 - secondStart - index).value
                    - secondMean - secondSlope * position;
            cross += firstValue * secondValue;
            firstPower += firstValue * firstValue;
            secondPower += secondValue * secondValue;
        }
        return firstPower == 0.0 || secondPower == 0.0 ? Double.NEGATIVE_INFINITY
                : cross / Math.sqrt(firstPower * secondPower);
    }

    private record Sample(double x, double y, double value) {
    }

    private record Line(List<Sample> samples, double centerX, double centerY, double xDirection, double yDirection,
                        double sampleSpacing) {
    }

    private record PairEstimate(int shift, double correlation, double sampleSpacing) {
    }
}
