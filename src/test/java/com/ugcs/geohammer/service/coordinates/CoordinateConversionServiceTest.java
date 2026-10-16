package com.ugcs.geohammer.service.coordinates;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.Column;
import com.ugcs.geohammer.model.ColumnSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordinateConversionServiceTest {

    private final CoordinateConversionService service = new CoordinateConversionService(new CoordinateReferenceService());

    @Test
    void convert_transformsWgs84ToUtm() {
        CoordinateConversionService.CoordinateConversionResult result = service.convert(createData(), "Longitude", "Latitude",
                "EPSG:4326", "32618");

        assertEquals(612058.64, result.xValues().getFirst().doubleValue(), 0.02);
        assertEquals(4897820.62, result.yValues().getFirst().doubleValue(), 0.02);
        assertNull(result.xValues().get(1));
        assertNull(result.yValues().get(1));
    }

    @Test
    void convert_transformsToNad83Utm() {
        CoordinateConversionService.CoordinateConversionResult result = service.convert(createData(), "Longitude", "Latitude",
                "4326", "26918");

        assertEquals(612058.64, result.xValues().getFirst().doubleValue(), 0.02);
        assertEquals(4897820.62, result.yValues().getFirst().doubleValue(), 0.02);
    }

    private static List<GeoData> createData() {
        ColumnSchema schema = new ColumnSchema();
        schema.addColumn(new Column("Longitude"));
        schema.addColumn(new Column("Latitude"));
        GeoData value = new GeoData(schema);
        value.setValue("Longitude", -73.597);
        value.setValue("Latitude", 44.225);
        GeoData missing = new GeoData(schema);
        missing.setValue("Longitude", -73.598);
        return List.of(value, missing);
    }
}
