package com.ugcs.geohammer.service.magnetics;

public record GnssLagEstimate(int shiftMeasurements, double correlation, int linePairCount,
                              double approximateDistanceMeters) {
}
