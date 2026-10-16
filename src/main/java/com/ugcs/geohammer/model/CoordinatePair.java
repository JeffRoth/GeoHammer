package com.ugcs.geohammer.model;

import com.ugcs.geohammer.util.Check;

public record CoordinatePair(String xSeries, String ySeries, int epsgCode) {

    public CoordinatePair {
        Check.notEmpty(xSeries);
        Check.notEmpty(ySeries);
        Check.condition(!xSeries.equals(ySeries), "Coordinate X and Y series must differ");
        Check.condition(epsgCode > 0, "EPSG code must be positive");
    }
}
