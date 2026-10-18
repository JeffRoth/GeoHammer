package com.ugcs.geohammer.service.magnetics;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.ColumnSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiurnalCorrectionServiceTest {

    private static final String FIELD = "TMI";

    private final DiurnalCorrectionService service = new DiurnalCorrectionService();

    @Test
    void correct_interpolatesBaseStationValuesUsingMedianReference() {
        List<GeoData> survey = List.of(sample(0L, 100), sample(5L, 110), sample(10L, 120));
        List<GeoData> base = List.of(sample(0L, 50), sample(10L, 70));

        DiurnalCorrectionResult result = service.correct(survey, FIELD, base, FIELD, null);

        assertEquals(60, result.referenceField());
        assertEquals(List.of(110.0, 110.0, 110.0), result.values());
    }

    @Test
    void correct_usesConfiguredReferenceField() {
        List<GeoData> survey = List.of(sample(0L, 100), sample(10L, 120));
        List<GeoData> base = List.of(sample(0L, 50), sample(10L, 70));

        DiurnalCorrectionResult result = service.correct(survey, FIELD, base, FIELD, 55.0);

        assertEquals(55, result.referenceField());
        assertEquals(List.of(105.0, 105.0), result.values());
    }

    @Test
    void correct_rejectsSurveyOutsideBaseStationTimeRange() {
        List<GeoData> survey = List.of(sample(-1L, 100), sample(10L, 120));
        List<GeoData> base = List.of(sample(0L, 50), sample(10L, 70));

        assertThrows(IllegalArgumentException.class,
                () -> service.correct(survey, FIELD, base, FIELD, null));
    }

    @Test
    void correct_rejectsMagneticSampleWithoutTimestamp() {
        GeoData survey = sample(null, 100);
        List<GeoData> base = List.of(sample(0L, 50), sample(10L, 70));

        assertThrows(IllegalArgumentException.class,
                () -> service.correct(List.of(survey), FIELD, base, FIELD, null));
    }

    @Test
    void result_preservesMissingValuesFromLaggedInput() {
        List<Number> values = new ArrayList<>();
        values.add(null);
        values.add(100.0);

        DiurnalCorrectionResult result = new DiurnalCorrectionResult(values, 50.0);

        assertNull(result.values().getFirst());
        assertEquals(100.0, result.values().get(1));
    }

    private static GeoData sample(Long timestamp, double field) {
        ColumnSchema schema = new ColumnSchema();
        schema.addColumn(new com.ugcs.geohammer.model.Column(FIELD));
        GeoData value = new GeoData(schema);
        value.setTimestamp(timestamp);
        value.setValue(FIELD, field);
        return value;
    }
}
