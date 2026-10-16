package com.ugcs.geohammer.service.coordinates;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.util.Check;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CoordinateConversionService {

    private final CoordinateReferenceService coordinates;

    public CoordinateConversionService(CoordinateReferenceService coordinates) {
        this.coordinates = coordinates;
    }

    public CoordinateConversionResult convert(List<GeoData> data, String xSeries, String ySeries,
                                              String sourceEpsg, String targetEpsg) {
        Check.notEmpty(data);
        Check.notEmpty(xSeries);
        Check.notEmpty(ySeries);
        if (xSeries.equals(ySeries)) {
            throw new IllegalArgumentException("X and Y series must be different");
        }
        ColumnSchema schema = GeoData.getSchema(data);
        if (schema == null || schema.getColumn(xSeries) == null || schema.getColumn(ySeries) == null) {
            throw new IllegalArgumentException("Select two available coordinate series");
        }

        int sourceCode = CoordinateReferenceService.parseEpsg(sourceEpsg);
        int targetCode = CoordinateReferenceService.parseEpsg(targetEpsg);
        coordinates.getCrs(sourceCode);
        coordinates.getCrs(targetCode);
        List<@Nullable Number> xValues = new ArrayList<>(data.size());
        List<@Nullable Number> yValues = new ArrayList<>(data.size());
        for (GeoData value : data) {
            Number x = value.getNumber(xSeries);
            Number y = value.getNumber(ySeries);
            if (x == null || y == null) {
                xValues.add(null);
                yValues.add(null);
                continue;
            }
            try {
                CoordinatePoint projected = coordinates.transform(x.doubleValue(), y.doubleValue(), sourceCode, targetCode);
                if (!Double.isFinite(projected.x()) || !Double.isFinite(projected.y())) {
                    xValues.add(null);
                    yValues.add(null);
                } else {
                    xValues.add(projected.x());
                    yValues.add(projected.y());
                }
            } catch (RuntimeException e) {
                xValues.add(null);
                yValues.add(null);
            }
        }
        return new CoordinateConversionResult(xValues, yValues);
    }

    public record CoordinateConversionResult(List<@Nullable Number> xValues, List<@Nullable Number> yValues) {
    }
}
