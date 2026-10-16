package com.ugcs.geohammer.service.gridding;

import com.ugcs.geohammer.model.LatLon;
import com.ugcs.geohammer.service.coordinates.CoordinatePoint;
import com.ugcs.geohammer.service.coordinates.CoordinateReferenceService;

public record GridCoordinates(double minX, double minY, double maxX, double maxY, int epsgCode) {

    public GridCoordinates expand(double distance) {
        return new GridCoordinates(minX - distance, minY - distance, maxX + distance, maxY + distance, epsgCode);
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }

    public double x(LatLon location, CoordinateReferenceService coordinates) {
        return project(location, coordinates).x();
    }

    public double y(LatLon location, CoordinateReferenceService coordinates) {
        return project(location, coordinates).y();
    }

    private CoordinatePoint project(LatLon location, CoordinateReferenceService coordinates) {
        return coordinates.project(location, epsgCode);
    }
}
