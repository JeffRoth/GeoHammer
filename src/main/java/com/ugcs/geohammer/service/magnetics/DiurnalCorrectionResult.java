package com.ugcs.geohammer.service.magnetics;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record DiurnalCorrectionResult(List<@Nullable Number> values, double referenceField) {

    public DiurnalCorrectionResult {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }
}
