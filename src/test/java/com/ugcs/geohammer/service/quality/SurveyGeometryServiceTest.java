package com.ugcs.geohammer.service.quality;

import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.Column;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.Semantic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurveyGeometryServiceTest {

    @Test
    void analyze_summarizesLineGeometryAndRecommendations() {
        SurveyGeometryReport report = service().analyze(List.of(
                point(1, 44.0, -73.0), point(1, 44.01, -73.0), point(1, 44.02, -73.0),
                point(2, 44.0, -72.997), point(2, 44.01, -72.997), point(2, 44.02, -72.997),
                point(3, 44.0, -73.002), point(3, 44.0, -72.99), point(3, 44.0, -72.978),
                point(4, 44.0225, -73.002), point(4, 44.0225, -72.99), point(4, 44.0225, -72.978)));

        assertEquals(4, report.lineCount());
        assertEquals(2, report.primaryLineCount());
        assertEquals(2, report.tieLineCount());
        assertEquals(1, report.primaryLineSpacing().count());
        assertEquals(8, report.heading().count());
        assertEquals(2, report.tieLinePeakCount());
        assertEquals(report.primaryLineSpacing().median() / 4.0, report.recommendedCellSize(), 1e-9);
        assertEquals(report.primaryLineSpacing().median() * 2.0, report.recommendedBlankingDistance(), 1e-9);
        assertTrue(report.tieLinePeakSpacing() > report.primaryLineSpacing().median());
    }

    @Test
    void analyze_findsTieLinePeaksDespiteAnIrregularLine() {
        List<GeoData> data = new ArrayList<>();
        for (int line = 0; line < 6; line++) {
            double longitude = -73.0 + line * 0.003;
            addLine(data, line + 1, 44.0, longitude, 44.01, longitude);
        }
        addLine(data, 7, 44.0, -73.002, 44.0, -72.978);
        addLine(data, 8, 44.0225, -73.002, 44.0225, -72.978);
        addLine(data, 9, 44.045, -73.002, 44.045, -72.978);
        addLine(data, 10, 44.046, -73.002, 44.046, -72.978);
        addLine(data, 11, 44.0675, -73.002, 44.0675, -72.978);

        SurveyGeometryReport report = service().analyze(data);

        assertEquals(2_500.0, report.tieLinePeakSpacing(), 250.0);
        assertEquals(4, report.tieLinePeakCount());
    }

    @Test
    void analyze_usesOneOffsetForSeparatedFragmentsOfTheSameLine() {
        List<GeoData> data = new ArrayList<>();
        addLine(data, 1, 44.000, -73.00000, 44.010, -73.00000);
        addLine(data, 2, 44.000, -72.99999, 44.010, -72.99999);
        addLine(data, 3, 44.000, -72.99998, 44.010, -72.99998);
        addLine(data, 1, 44.020, -72.999999, 44.030, -72.999999);
        addLine(data, 2, 44.020, -72.99999, 44.030, -72.99999);
        addLine(data, 3, 44.020, -72.99998, 44.030, -72.99998);

        SurveyGeometryReport report = service().analyze(data);

        assertEquals(2, report.primaryLineSpacing().count());
        assertEquals(0.8, report.primaryLineSpacing().median(), 0.2);
    }

    @Test
    void analyze_refinesPrimaryHeadingBeforeCalculatingFragmentSpacing() {
        List<GeoData> data = new ArrayList<>();
        for (int line = 0; line < 5; line++) {
            double longitude = -73.0 + line * 0.0000125;
            addLineWithSamples(data, line + 1, 44.000, longitude, 44.020, longitude, 20);
        }
        for (int line = 0; line < 20; line++) {
            double longitude = -72.998 - line * 0.000001;
            addLine(data, line + 10, 44.000, longitude, 44.002, longitude + 0.000005);
        }

        SurveyGeometryReport report = service().analyze(data);

        assertTrue(Math.min(report.primaryLineOrientation(), 180.0 - report.primaryLineOrientation()) < 0.5);
        assertEquals(1.0, report.primaryLineSpacing().median(), 0.3);
    }

    @Test
    void analyze_ignoresNearSampleScaleOffsetsWhenPhysicalSpacingIsWellRepresented() {
        List<GeoData> data = new ArrayList<>();
        for (int line = 0; line < 8; line++) {
            double longitude = -73.0 + line * 0.0000125;
            addLineWithSamples(data, line + 1, 44.000, longitude, 44.000005, longitude, 12);
        }
        for (int fragment = 0; fragment < 20; fragment++) {
            double longitude = -73.0003 + fragment * 0.0000006;
            addLineWithSamples(data, fragment + 20, 44.001, longitude, 44.001005, longitude, 12);
        }

        SurveyGeometryReport report = service().analyze(data);

        assertEquals(1.0, report.primaryLineSpacing().median(), 0.3);
    }

    private static void addLine(List<GeoData> data, int line, double latitudeStart, double longitudeStart,
                                double latitudeEnd, double longitudeEnd) {
        data.add(point(line, latitudeStart, longitudeStart));
        data.add(point(line, latitudeEnd, longitudeEnd));
    }

    private static void addLineWithSamples(List<GeoData> data, int line, double latitudeStart, double longitudeStart,
                                           double latitudeEnd, double longitudeEnd, int samples) {
        for (int sample = 0; sample < samples; sample++) {
            double fraction = (double) sample / (samples - 1);
            data.add(point(line, latitudeStart + (latitudeEnd - latitudeStart) * fraction,
                    longitudeStart + (longitudeEnd - longitudeStart) * fraction));
        }
    }

    private static SurveyGeometryService service() {
        return new SurveyGeometryService();
    }

    private static GeoData point(int line, double latitude, double longitude) {
        ColumnSchema schema = new ColumnSchema();
        schema.addColumn(new Column("LINE").withSemantic(Semantic.LINE.getName()));
        schema.addColumn(new Column("LAT").withSemantic(Semantic.LATITUDE.getName()));
        schema.addColumn(new Column("LON").withSemantic(Semantic.LONGITUDE.getName()));
        GeoData point = new GeoData(schema);
        point.setLine(line);
        point.setLatitude(latitude);
        point.setLongitude(longitude);
        return point;
    }
}
