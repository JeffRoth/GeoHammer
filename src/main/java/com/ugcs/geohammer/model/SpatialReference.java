package com.ugcs.geohammer.model;

import com.ugcs.geohammer.util.Check;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record SpatialReference(List<CoordinatePair> coordinatePairs, @Nullable CoordinatePair activePair,
                               @Nullable CoordinatePair geographicPair) {

    public SpatialReference {
        Check.notNull(coordinatePairs);
        coordinatePairs = List.copyOf(coordinatePairs);
        if (activePair != null) {
            Check.condition(coordinatePairs.contains(activePair), "Active coordinates must be registered");
        }
        if (geographicPair != null) {
            Check.condition(coordinatePairs.contains(geographicPair), "Geographic coordinates must be registered");
        }
    }

    public SpatialReference withActivePair(CoordinatePair pair) {
        return new SpatialReference(addPair(pair), pair, geographicPair);
    }

    public SpatialReference withCoordinatePair(CoordinatePair pair) {
        return new SpatialReference(addPair(pair), activePair, geographicPair);
    }

    public SpatialReference withGeographicPair(CoordinatePair pair) {
        return new SpatialReference(addPair(pair), activePair, pair);
    }

    private List<CoordinatePair> addPair(CoordinatePair pair) {
        if (coordinatePairs.contains(pair)) {
            return coordinatePairs;
        }
        java.util.ArrayList<CoordinatePair> pairs = new java.util.ArrayList<>(coordinatePairs);
        pairs.add(pair);
        return pairs;
    }
}
