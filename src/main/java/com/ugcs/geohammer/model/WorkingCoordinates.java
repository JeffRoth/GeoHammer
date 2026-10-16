package com.ugcs.geohammer.model;

import com.ugcs.geohammer.util.Check;

public record WorkingCoordinates(String xSeries, String ySeries, int epsgCode) {

    public WorkingCoordinates {
        Check.notEmpty(xSeries);
        Check.notEmpty(ySeries);
        Check.condition(!xSeries.equals(ySeries), "Working X and Y series must differ");
    }
}
