package com.ugcs.geohammer.service.magnetics;

public enum HeadingCorrectionMethod {

    CROSSOVER_LEAST_SQUARES("Crossover least squares (recommended)"),
    RECIPROCAL_DIRECTION_MEDIAN("Statistical directional correction (no tie lines)");

    private final String displayName;

    HeadingCorrectionMethod(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
