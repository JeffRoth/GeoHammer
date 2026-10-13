package com.ugcs.geohammer.service.math;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.Column;
import com.ugcs.geohammer.model.ColumnSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeriesMathServiceTest {

    private final SeriesMathService service = new SeriesMathService();

    @Test
    void evaluate_combinesSeriesAndFunctions() {
        List<GeoData> data = createData();

        SeriesMathService.SeriesMathResult result = service.evaluate(data, "sqrt({Mag A} ^ 2) + abs({Base-Mag})");

        assertEquals("Mag A", result.sourceSeries());
        assertEquals(15.0, result.values().getFirst());
        assertEquals(7.0, result.values().get(1));
    }

    @Test
    void evaluate_returnsMissingValueWhenReferencedSeriesIsMissing() {
        List<GeoData> data = createData();
        data.get(1).setValue("Base-Mag", null);

        SeriesMathService.SeriesMathResult result = service.evaluate(data, "{Mag A} - {Base-Mag}");

        assertEquals(15.0, result.values().getFirst());
        assertNull(result.values().get(1));
    }

    @Test
    void evaluate_rejectsUnknownSeries() {
        assertThrows(IllegalArgumentException.class,
                () -> service.evaluate(createData(), "{Unknown} + 1"));
    }

    private static List<GeoData> createData() {
        ColumnSchema schema = new ColumnSchema();
        schema.addColumn(new Column("Mag A"));
        schema.addColumn(new Column("Base-Mag"));

        GeoData first = new GeoData(schema);
        first.setValue("Mag A", 10.0);
        first.setValue("Base-Mag", -5.0);
        GeoData second = new GeoData(schema);
        second.setValue("Mag A", -3.0);
        second.setValue("Base-Mag", 4.0);
        return List.of(first, second);
    }
}
