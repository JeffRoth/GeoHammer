package com.ugcs.geohammer.service.magnetics;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.Column;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.CoordinatePair;
import com.ugcs.geohammer.model.Semantic;
import com.ugcs.geohammer.model.SpatialReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrossoverLevelingServiceTest {

    private final CrossoverLevelingService service = new CrossoverLevelingService(
            new com.ugcs.geohammer.service.coordinates.CoordinateReferenceService());

    @Test
    void level_alignsSurveyLineWithTieLineAtCrossover() {
        List<GeoData> data = List.of(
                sample(1, -1, 0, 100), sample(1, 1, 0, 100),
                sample(2, 0, -1, 110), sample(2, 0, 1, 110));

        CrossoverLevelingResult result = service.level(data, "TMI", Set.of(2));

        assertEquals(1, result.crossovers().size());
        assertEquals(10, result.corrections().get(1));
        assertEquals(List.of(110.0, 110.0, 110.0, 110.0), result.values());
        assertEquals(10, result.rmsError());
    }

    @Test
    void level_usesActiveProjectedCoordinatesForCrossovers() {
        List<GeoData> data = List.of(
                projectedSample(1, 599900, 4890000, 100), projectedSample(1, 600100, 4890000, 100),
                projectedSample(2, 600000, 4889900, 110), projectedSample(2, 600000, 4890100, 110));

        CrossoverLevelingResult result = service.level(data, "TMI", Set.of(2));

        assertEquals(1, result.crossovers().size());
        assertEquals(10, result.corrections().get(1));
    }

    private static GeoData sample(int line, double latitude, double longitude, double field) {
        ColumnSchema schema = new ColumnSchema();
        schema.addColumn(new Column("Line").withSemantic(Semantic.LINE.getName()));
        schema.addColumn(new Column("Latitude").withSemantic(Semantic.LATITUDE.getName()));
        schema.addColumn(new Column("Longitude").withSemantic(Semantic.LONGITUDE.getName()));
        schema.addColumn(new Column("TMI"));
        GeoData value = new GeoData(schema);
        value.setLine(line);
        value.setLatitude(latitude);
        value.setLongitude(longitude);
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
