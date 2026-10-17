package com.ugcs.geohammer.service.magnetics;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.Column;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.CoordinatePair;
import com.ugcs.geohammer.model.LatLon;
import com.ugcs.geohammer.model.Semantic;
import com.ugcs.geohammer.model.SpatialReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadingCorrectionServiceTest {

    private final HeadingCorrectionService service = new HeadingCorrectionService(
            new com.ugcs.geohammer.service.coordinates.CoordinateReferenceService());

    @Test
    void correct_removesHeadingBiasAtCrossovers() {
        List<GeoData> data = List.of(
                sample(1, 0, 0, 100), sample(1, 0, 2, 100),
                sample(2, -1, 1, 110), sample(2, 1, 1, 110)
        );

        HeadingCorrectionResult result = service.correct(data, "TMI", 8);

        assertEquals(List.of(100.0, 100.0, 100.0, 100.0), result.values());
        assertEquals(1, result.crossovers().size());
        assertEquals(0.0, result.rmsError());
    }

    @Test
    void correct_requiresCrossoverObservations() {
        List<GeoData> data = List.of(
                sample(1, 0, 0, 100), sample(1, 0, 1, 100),
                sample(2, 1, 0, 110), sample(2, 1, 1, 110)
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.correct(data, "TMI", 8));

        assertTrue(exception.getMessage().contains("parallel lines"));
    }

    @Test
    void correct_usesProjectedWorkingCoordinatesWithoutGeographicColumns() {
        List<GeoData> data = List.of(
                projectedSample(1, 500000, 4900000, 100), projectedSample(1, 500000, 4900200, 100),
                projectedSample(2, 499900, 4900100, 110), projectedSample(2, 500100, 4900100, 110)
        );

        HeadingCorrectionResult result = service.correct(data, "TMI", 8);

        assertEquals(List.of(100.0, 100.0, 100.0, 100.0), result.values());
        assertEquals(1, result.crossovers().size());
    }

    @Test
    void correctReciprocalDirections_balancesMedianOffsetsOfParallelLines() {
        List<GeoData> data = List.of(
                projectedSample(1, 500000, 4900000, 100), projectedSample(1, 500100, 4900000, 100),
                projectedSample(2, 500000, 4900100, 102), projectedSample(2, 500100, 4900100, 102),
                projectedSample(3, 500100, 4900200, 110), projectedSample(3, 500000, 4900200, 110),
                projectedSample(4, 500100, 4900300, 108), projectedSample(4, 500000, 4900300, 108)
        );

        ReciprocalHeadingCorrectionResult result = service.correctReciprocalDirections(data, "TMI");

        assertEquals(-4.0, result.directionOffset());
        assertEquals(2, result.forwardLineCount());
        assertEquals(2, result.reverseLineCount());
        assertEquals(List.of(104.0, 104.0, 106.0, 106.0, 106.0, 106.0, 104.0, 104.0), result.values());
    }

    private static GeoData sample(int line, double latitude, double longitude, double field) {
        ColumnSchema schema = new ColumnSchema();
        schema.addColumn(new Column("Line").withSemantic(Semantic.LINE.getName()));
        schema.addColumn(new Column("Latitude").withSemantic(Semantic.LATITUDE.getName()));
        schema.addColumn(new Column("Longitude").withSemantic(Semantic.LONGITUDE.getName()));
        schema.addColumn(new Column("TMI"));
        GeoData value = new GeoData(schema);
        value.setLine(line);
        value.setLatLon(new LatLon(latitude, longitude));
        value.setValue("TMI", field);
        return value;
    }

    private static GeoData projectedSample(int line, double x, double y, double field) {
        ColumnSchema schema = new ColumnSchema();
        schema.addColumn(new Column("Line").withSemantic(Semantic.LINE.getName()));
        schema.addColumn(new Column("X"));
        schema.addColumn(new Column("Y"));
        schema.addColumn(new Column("TMI"));
        CoordinatePair pair = new CoordinatePair("X", "Y", 26918);
        schema.setSpatialReference(new SpatialReference(List.of(pair), pair, null));
        GeoData value = new GeoData(schema);
        value.setLine(line);
        value.setValue("X", x);
        value.setValue("Y", y);
        value.setValue("TMI", field);
        return value;
    }
}
