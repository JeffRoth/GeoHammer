package com.ugcs.geohammer.service.coordinates;

import com.ugcs.geohammer.model.LatLon;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.referencing.CRS;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class CoordinateReferenceService {

    public static final int WGS84_EPSG = 4326;

    private final ConcurrentMap<Integer, CoordinateReferenceSystem> crsByCode = new ConcurrentHashMap<>();

    private final ConcurrentMap<TransformKey, MathTransform> transforms = new ConcurrentHashMap<>();

    public CoordinatePoint transform(double x, double y, int sourceEpsg, int targetEpsg) {
        if (sourceEpsg == targetEpsg) {
            return new CoordinatePoint(x, y);
        }
        MathTransform transform = transforms.computeIfAbsent(new TransformKey(sourceEpsg, targetEpsg),
                this::createTransform);
        double[] source = {x, y};
        double[] target = new double[2];
        try {
            transform.transform(source, 0, target, 0, 1);
        } catch (TransformException e) {
            throw new IllegalArgumentException("Cannot transform coordinate from EPSG:" + sourceEpsg
                    + " to EPSG:" + targetEpsg, e);
        }
        return new CoordinatePoint(target[0], target[1]);
    }

    public CoordinatePoint project(LatLon location, int targetEpsg) {
        return transform(location.getLonDgr(), location.getLatDgr(), WGS84_EPSG, targetEpsg);
    }

    public LatLon geographic(double x, double y, int sourceEpsg) {
        CoordinatePoint point = transform(x, y, sourceEpsg, WGS84_EPSG);
        return new LatLon(point.y(), point.x());
    }

    public boolean isValid(int epsgCode) {
        try {
            getCrs(epsgCode);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static int parseEpsg(String epsg) {
        if (epsg == null) {
            throw new IllegalArgumentException("Enter an EPSG code, for example EPSG:32618");
        }
        String normalized = epsg.trim().toUpperCase(Locale.ROOT).replace("EPSG:", "");
        try {
            int code = Integer.parseInt(normalized);
            if (code <= 0) {
                throw new NumberFormatException();
            }
            return code;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter an EPSG code, for example EPSG:32618");
        }
    }

    private MathTransform createTransform(TransformKey key) {
        try {
            return CRS.findMathTransform(getCrs(key.sourceEpsg()), getCrs(key.targetEpsg()), true);
        } catch (FactoryException e) {
            throw new IllegalArgumentException("Cannot create transformation from EPSG:" + key.sourceEpsg()
                    + " to EPSG:" + key.targetEpsg(), e);
        }
    }

    public CoordinateReferenceSystem getCrs(int epsgCode) {
        return crsByCode.computeIfAbsent(epsgCode, this::decode);
    }

    private CoordinateReferenceSystem decode(int epsgCode) {
        try {
            return CRS.decode("EPSG:" + epsgCode, true);
        } catch (FactoryException e) {
            throw new IllegalArgumentException("Unknown or unsupported CRS EPSG:" + epsgCode, e);
        }
    }

    private record TransformKey(int sourceEpsg, int targetEpsg) {
    }
}
