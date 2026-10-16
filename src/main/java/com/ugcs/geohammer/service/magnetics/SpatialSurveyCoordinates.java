package com.ugcs.geohammer.service.magnetics;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.CoordinatePair;
import com.ugcs.geohammer.model.LatLon;
import com.ugcs.geohammer.model.Semantic;
import com.ugcs.geohammer.service.coordinates.CoordinateReferenceService;
import com.ugcs.geohammer.util.Check;
import org.jspecify.annotations.Nullable;

import java.util.List;

final class SpatialSurveyCoordinates {

    private final CoordinatePair pair;

    private final CoordinateReferenceService coordinates;

    private SpatialSurveyCoordinates(CoordinatePair pair, CoordinateReferenceService coordinates) {
        this.pair = pair;
        this.coordinates = coordinates;
    }

    static SpatialSurveyCoordinates of(List<GeoData> data, CoordinateReferenceService coordinates) {
        ColumnSchema schema = GeoData.getSchema(data);
        CoordinatePair pair = schema != null && schema.getSpatialReference() != null
                ? schema.getSpatialReference().activePair()
                : null;
        if (pair == null) {
            String longitude = schema != null ? schema.getHeaderBySemantic(Semantic.LONGITUDE.getName()) : null;
            String latitude = schema != null ? schema.getHeaderBySemantic(Semantic.LATITUDE.getName()) : null;
            Check.condition(longitude != null && latitude != null,
                    "Select working coordinates before performing spatial magnetic processing");
            pair = new CoordinatePair(longitude, latitude, CoordinateReferenceService.WGS84_EPSG);
        }
        return new SpatialSurveyCoordinates(pair, coordinates);
    }

    @Nullable SpatialPoint point(GeoData value) {
        Number x = value.getNumber(pair.xSeries());
        Number y = value.getNumber(pair.ySeries());
        if (x == null || y == null) {
            return null;
        }
        return new SpatialPoint(x.doubleValue(), y.doubleValue(), value.getLatLon());
    }

    double distance(SpatialPoint first, SpatialPoint second) {
        if (pair.epsgCode() != CoordinateReferenceService.WGS84_EPSG) {
            return Math.hypot(second.x() - first.x(), second.y() - first.y());
        }
        if (first.mapLocation() != null && second.mapLocation() != null) {
            return first.mapLocation().getDistance(second.mapLocation());
        }
        return Math.hypot(second.x() - first.x(), second.y() - first.y());
    }

    boolean isGeographic() {
        return pair.epsgCode() == CoordinateReferenceService.WGS84_EPSG;
    }

    SpatialPoint transform(SpatialPoint point, int targetEpsg) {
        if (pair.epsgCode() == targetEpsg) {
            return point;
        }
        var transformed = coordinates.transform(point.x(), point.y(), pair.epsgCode(), targetEpsg);
        return new SpatialPoint(transformed.x(), transformed.y(), point.mapLocation());
    }

    LatLon geographic(double x, double y) {
        return pair.epsgCode() == CoordinateReferenceService.WGS84_EPSG
                ? new LatLon(y, x)
                : coordinates.geographic(x, y, pair.epsgCode());
    }

    record SpatialPoint(double x, double y, @Nullable LatLon mapLocation) {
    }
}
