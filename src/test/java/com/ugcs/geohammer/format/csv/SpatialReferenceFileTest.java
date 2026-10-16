package com.ugcs.geohammer.format.csv;

import com.ugcs.geohammer.model.Column;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.CoordinatePair;
import com.ugcs.geohammer.model.SpatialReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpatialReferenceFileTest {

    @Test
    void persistsCoordinatePairsAndSelections() throws Exception {
        Path data = Files.createTempFile("geohammer-spatial", ".csv");
        try {
            ColumnSchema schema = createSchema();
            CoordinatePair geographic = new CoordinatePair("LON", "LAT", 4326);
            CoordinatePair projected = new CoordinatePair("EASTING", "NORTHING", 26918);
            schema.setSpatialReference(new SpatialReference(List.of(geographic, projected), projected, geographic));

            SpatialReferenceFile.save(data.toFile(), schema);
            ColumnSchema restored = createSchema();
            SpatialReferenceFile.load(data.toFile(), restored);

            assertEquals(projected, restored.getSpatialReference().activePair());
            assertEquals(geographic, restored.getSpatialReference().geographicPair());
            assertEquals(List.of(geographic, projected), restored.getSpatialReference().coordinatePairs());
        } finally {
            Files.deleteIfExists(data);
            Files.deleteIfExists(Path.of(data + ".geohammer-spatial.properties"));
        }
    }

    private static ColumnSchema createSchema() {
        ColumnSchema schema = new ColumnSchema();
        schema.addColumn(new Column("LON"));
        schema.addColumn(new Column("LAT"));
        schema.addColumn(new Column("EASTING"));
        schema.addColumn(new Column("NORTHING"));
        return schema;
    }
}
