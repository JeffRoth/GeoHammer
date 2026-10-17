package com.ugcs.geohammer.service.magnetics;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record ReciprocalHeadingCorrectionResult(List<@Nullable Number> values, double directionOffset,
                                                int forwardLineCount, int reverseLineCount) {
}
