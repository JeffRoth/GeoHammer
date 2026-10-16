package com.ugcs.geohammer.service.magnetics;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.service.coordinates.CoordinateReferenceService;
import com.ugcs.geohammer.util.Check;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RegionalRemovalService {

    private final CoordinateReferenceService coordinates;

    public RegionalRemovalService(CoordinateReferenceService coordinates) {
        this.coordinates = coordinates;
    }

    public RegionalRemovalResult remove(List<GeoData> data, String series, int polynomialOrder) {
        Check.notEmpty(data);
        Check.notEmpty(series);
        Check.condition(polynomialOrder >= 0 && polynomialOrder <= 3,
                "Polynomial order must be between 0 and 3");

        SpatialSurveyCoordinates spatial = SpatialSurveyCoordinates.of(data, coordinates);
        List<Sample> samples = collectSamples(data, series, spatial);
        int terms = termCount(polynomialOrder);
        Check.condition(samples.size() >= terms,
                "Polynomial order %d requires at least %d samples with coordinates and values"
                        .formatted(polynomialOrder, terms));
        normalizeCoordinates(samples, spatial.isGeographic());

        double[][] normal = new double[terms][terms];
        double[] rightHandSide = new double[terms];
        for (Sample sample : samples) {
            double[] basis = basis(sample.x, sample.y, polynomialOrder);
            for (int row = 0; row < terms; row++) {
                rightHandSide[row] += basis[row] * sample.field;
                for (int column = 0; column < terms; column++) {
                    normal[row][column] += basis[row] * basis[column];
                }
            }
        }
        double[] coefficients = solve(normal, rightHandSide);
        double[] residuals = new double[data.size()];
        boolean[] hasResidual = new boolean[data.size()];
        double sumSquares = 0;
        for (Sample sample : samples) {
            double residual = sample.field - evaluate(coefficients, basis(sample.x, sample.y, polynomialOrder));
            residuals[sample.dataIndex] = residual;
            hasResidual[sample.dataIndex] = true;
            sumSquares += residual * residual;
        }

        List<@Nullable Number> values = new ArrayList<>(data.size());
        for (int index = 0; index < data.size(); index++) {
            values.add(hasResidual[index] ? residuals[index] : null);
        }
        return new RegionalRemovalResult(values, samples.size(), Math.sqrt(sumSquares / samples.size()));
    }

    private static List<Sample> collectSamples(List<GeoData> data, String series, SpatialSurveyCoordinates spatial) {
        List<Sample> samples = new ArrayList<>();
        for (int index = 0; index < data.size(); index++) {
            GeoData value = data.get(index);
            SpatialSurveyCoordinates.SpatialPoint location = spatial.point(value);
            Number field = value.getNumber(series);
            if (location != null && field != null && Double.isFinite(field.doubleValue())) {
                samples.add(new Sample(index, location.x(), location.y(), field.doubleValue()));
            }
        }
        Check.condition(!samples.isEmpty(), "Survey has no samples with coordinates and magnetic values");
        return samples;
    }

    private static void normalizeCoordinates(List<Sample> samples, boolean geographic) {
        double xSum = 0;
        double ySum = 0;
        for (Sample sample : samples) {
            xSum += sample.sourceX;
            ySum += sample.sourceY;
        }
        double xCenter = xSum / samples.size();
        double yCenter = ySum / samples.size();
        double xScale = geographic ? Math.cos(Math.toRadians(yCenter)) : 1.0;
        double scale = 0;
        for (Sample sample : samples) {
            sample.x = (sample.sourceX - xCenter) * xScale;
            sample.y = sample.sourceY - yCenter;
            scale = Math.max(scale, Math.max(Math.abs(sample.x), Math.abs(sample.y)));
        }
        Check.condition(scale > 1e-12, "Survey coordinates do not span an area");
        for (Sample sample : samples) {
            sample.x /= scale;
            sample.y /= scale;
        }
    }

    private static int termCount(int polynomialOrder) {
        return (polynomialOrder + 1) * (polynomialOrder + 2) / 2;
    }

    private static double[] basis(double x, double y, int polynomialOrder) {
        double[] basis = new double[termCount(polynomialOrder)];
        int index = 0;
        for (int degree = 0; degree <= polynomialOrder; degree++) {
            for (int xPower = 0; xPower <= degree; xPower++) {
                basis[index++] = Math.pow(x, xPower) * Math.pow(y, degree - xPower);
            }
        }
        return basis;
    }

    private static double evaluate(double[] coefficients, double[] basis) {
        double result = 0;
        for (int index = 0; index < coefficients.length; index++) {
            result += coefficients[index] * basis[index];
        }
        return result;
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
                    "Survey geometry cannot support this polynomial order");
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

    private static class Sample {

        private final int dataIndex;

        private final double sourceX;

        private final double sourceY;

        private final double field;

        private double x;

        private double y;

        private Sample(int dataIndex, double sourceX, double sourceY, double field) {
            this.dataIndex = dataIndex;
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.field = field;
        }
    }
}
