package com.ugcs.geohammer.service.math;

import com.ezylang.evalex.BaseException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.data.EvaluationValue;
import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.util.Check;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SeriesMathService {

    private static final Pattern SERIES_REFERENCE = Pattern.compile("\\{([^{}]+)}");

    public SeriesMathResult evaluate(List<GeoData> data, String expression) {
        Check.notEmpty(data);
        Check.notEmpty(expression);

        ColumnSchema schema = GeoData.getSchema(data);
        Check.condition(schema != null, "Dataset has no column schema");
        PreparedExpression prepared = prepareExpression(schema, expression);
        Expression evaluator = new Expression(prepared.expression());
        List<@Nullable Number> values = new ArrayList<>(data.size());
        Map<String, Number> variables = new LinkedHashMap<>();
        for (int index = 0; index < data.size(); index++) {
            variables.clear();
            boolean missingValue = false;
            for (Map.Entry<String, String> reference : prepared.references().entrySet()) {
                Number value = data.get(index).getNumber(reference.getKey());
                if (value == null || !Double.isFinite(value.doubleValue())) {
                    missingValue = true;
                    break;
                }
                variables.put(reference.getValue(), value);
            }
            if (missingValue) {
                values.add(null);
                continue;
            }
            try {
                EvaluationValue result = evaluator.copy().withValues(variables).evaluate();
                Check.condition(result.isNumberValue(), "Expression must evaluate to a number");
                double value = result.getNumberValue().doubleValue();
                values.add(Double.isFinite(value) ? value : null);
            } catch (BaseException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot evaluate expression at measurement " + (index + 1)
                        + ": " + e.getMessage(), e);
            }
        }
        return new SeriesMathResult(prepared.references().keySet().iterator().next(),
                Collections.unmodifiableList(new ArrayList<>(values)));
    }

    private static PreparedExpression prepareExpression(ColumnSchema schema, String expression) {
        Matcher matcher = SERIES_REFERENCE.matcher(expression);
        StringBuffer normalized = new StringBuffer();
        Map<String, String> references = new LinkedHashMap<>();
        while (matcher.find()) {
            String header = matcher.group(1).trim();
            Check.notEmpty(header, "Expression contains an empty series reference");
            Check.condition(schema.getColumn(header) != null, "Series '" + header + "' is not available");
            String variable = references.computeIfAbsent(header, ignored -> "v" + (references.size() + 1));
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(variable));
        }
        matcher.appendTail(normalized);
        Check.condition(!references.isEmpty(), "Expression must reference at least one series using {Series name}");
        return new PreparedExpression(normalized.toString(), references);
    }

    public record SeriesMathResult(String sourceSeries, List<@Nullable Number> values) {
    }

    private record PreparedExpression(String expression, Map<String, String> references) {
    }
}
