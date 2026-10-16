package com.ugcs.geohammer.chart.tool;

import com.ugcs.geohammer.chart.csv.SensorLineChart;
import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.format.SgyFile;
import com.ugcs.geohammer.format.csv.CsvFile;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.CoordinatePair;
import com.ugcs.geohammer.model.SpatialReference;
import com.ugcs.geohammer.model.Model;
import com.ugcs.geohammer.model.Semantic;
import com.ugcs.geohammer.model.WorkingCoordinates;
import com.ugcs.geohammer.model.event.FileSelectedEvent;
import com.ugcs.geohammer.model.event.FileUpdatedEvent;
import com.ugcs.geohammer.model.event.SeriesUpdatedEvent;
import com.ugcs.geohammer.service.coordinates.CoordinateConversionService;
import com.ugcs.geohammer.service.coordinates.CoordinateReferenceService;
import com.ugcs.geohammer.util.Nulls;
import com.ugcs.geohammer.view.Dialogs;
import com.ugcs.geohammer.view.control.InputWithTopLabel;
import com.ugcs.geohammer.view.control.NodeWithTopLabel;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

@Component
public class CoordinateConversionTool extends FilterToolView {

    private final Model model;

    private final CoordinateConversionService conversionService;

    private final ComboBox<String> xSeriesSelector;

    private final ComboBox<String> ySeriesSelector;

    private final ComboBox<CrsOption> sourceCrsSelector;

    private final ComboBox<CrsOption> targetCrsSelector;

    private final TextField outputXInput;

    private final TextField outputYInput;

    private final ComboBox<String> workingXSelector;

    private final ComboBox<String> workingYSelector;

    private final ComboBox<CrsOption> workingCrsSelector;

    private final Label workingCoordinatesLabel;

    private @Nullable SgyFile inputSelectorFile;

    private @Nullable String automaticOutputX;

    private @Nullable String automaticOutputY;

    public CoordinateConversionTool(Model model, CoordinateConversionService conversionService, ExecutorService executor) {
        super(executor);
        this.model = model;
        this.conversionService = conversionService;

        xSeriesSelector = createSelector();
        ySeriesSelector = createSelector();
        workingXSelector = createSelector();
        workingYSelector = createSelector();
        workingXSelector.setOnAction(event -> updateWorkingCrsForSelection());
        workingYSelector.setOnAction(event -> updateWorkingCrsForSelection());

        sourceCrsSelector = createCrsSelector("4326");
        targetCrsSelector = createCrsSelector("32618");
        workingCrsSelector = createCrsSelector("4326");
        outputXInput = new TextField("X_CONVERTED");
        outputYInput = new TextField("Y_CONVERTED");
        Button setWorkingCoordinatesButton = new Button("Use selected working coordinates");
        setWorkingCoordinatesButton.setOnAction(event -> setWorkingCoordinates());
        workingCoordinatesLabel = new Label();
        workingCoordinatesLabel.setWrapText(true);
        outputXInput.textProperty().addListener((observable, oldValue, newValue) -> validateInput());
        outputYInput.textProperty().addListener((observable, oldValue, newValue) -> validateInput());
        targetCrsSelector.setOnAction(event -> {
            updateOutputNames();
            validateInput();
        });
        updateOutputNames();
        buttonContainer.getChildren().remove(applyButton);
        buttonContainer.setManaged(false);
        buttonContainer.setVisible(false);

        inputContainer.getChildren().setAll(
                new NodeWithTopLabel<>("Input X / longitude series", xSeriesSelector),
                new NodeWithTopLabel<>("Input Y / latitude series", ySeriesSelector),
                new NodeWithTopLabel<>("Source CRS", sourceCrsSelector),
                new NodeWithTopLabel<>("Target CRS", targetCrsSelector),
                new Label("Use X then Y for projected CRS, or longitude then latitude for EPSG:4326."),
                new Label("Choose WGS84 geographic, Web Mercator, or a WGS84 UTM zone."),
                new NodeWithTopLabel<>("New X / longitude series name", outputXInput),
                new NodeWithTopLabel<>("New Y / latitude series name", outputYInput),
                applyButton,
                new Label("Working coordinates"),
                new NodeWithTopLabel<>("Working X / longitude series", workingXSelector),
                new NodeWithTopLabel<>("Working Y / latitude series", workingYSelector),
                new NodeWithTopLabel<>("Working CRS", workingCrsSelector),
                setWorkingCoordinatesButton,
                workingCoordinatesLabel
        );
        showApply(true);
    }

    @Override
    public boolean isVisibleFor(SgyFile file) {
        return file instanceof CsvFile;
    }

    @Override
    public void updateView() {
        updateSeriesSelectors();
        updateWorkingCoordinatesLabel();
        validateInput();
    }

    private ComboBox<String> createSelector() {
        ComboBox<String> selector = new ComboBox<>();
        selector.setMaxWidth(Double.MAX_VALUE);
        selector.setOnAction(event -> validateInput());
        return selector;
    }

    private ComboBox<CrsOption> createCrsSelector(String selectedEpsg) {
        ComboBox<CrsOption> selector = new ComboBox<>();
        selector.getItems().setAll(createCrsOptions());
        selector.setValue(findCrsOption(selector.getItems(), selectedEpsg));
        selector.setEditable(true);
        selector.setConverter(new StringConverter<>() {
            @Override
            public String toString(CrsOption option) {
                return option == null ? "" : option.toString();
            }

            @Override
            public CrsOption fromString(String text) {
                String epsg = text == null ? "" : text.trim().replaceFirst("(?i)^EPSG:", "");
                return findCrsOption(selector.getItems(), epsg);
            }
        });
        selector.setMaxWidth(Double.MAX_VALUE);
        selector.setOnAction(event -> validateInput());
        return selector;
    }

    private static List<CrsOption> createCrsOptions() {
        List<CrsOption> options = new ArrayList<>();
        options.add(new CrsOption("WGS 84 geographic (EPSG:4326)", "4326"));
        options.add(new CrsOption("Web Mercator (EPSG:3857)", "3857"));
        for (int zone = 1; zone <= 60; zone++) {
            options.add(new CrsOption("WGS 84 / UTM zone %dN (EPSG:%d)".formatted(zone, 32600 + zone),
                    Integer.toString(32600 + zone)));
        }
        for (int zone = 1; zone <= 60; zone++) {
            options.add(new CrsOption("WGS 84 / UTM zone %dS (EPSG:%d)".formatted(zone, 32700 + zone),
                    Integer.toString(32700 + zone)));
        }
        return options;
    }

    private static CrsOption findCrsOption(List<CrsOption> options, String epsg) {
        for (CrsOption option : options) {
            if (option.epsg().equals(epsg)) {
                return option;
            }
        }
        return new CrsOption("EPSG:" + epsg, epsg);
    }

    private void updateSeriesSelectors() {
        String selectedX = xSeriesSelector.getValue();
        String selectedY = ySeriesSelector.getValue();
        ColumnSchema schema = GeoData.getSchema(selectedFile != null ? selectedFile.getGeoData() : null);
        CoordinatePair defaults = !Objects.equals(inputSelectorFile, selectedFile) ? getInputCoordinatePair(schema) : null;
        MagneticSeriesSelector.update(xSeriesSelector, selectedFile, selectedX);
        MagneticSeriesSelector.update(ySeriesSelector, selectedFile, selectedY);
        if (defaults != null) {
            xSeriesSelector.setValue(defaults.xSeries());
            ySeriesSelector.setValue(defaults.ySeries());
            sourceCrsSelector.setValue(findCrsOption(sourceCrsSelector.getItems(), Integer.toString(defaults.epsgCode())));
            CrsOption suggestedUtm = getSuggestedUtmCrs(selectedFile, defaults);
            if (suggestedUtm != null) {
                targetCrsSelector.setValue(suggestedUtm);
                updateOutputNames();
            }
        } else if (selectedY == null && ySeriesSelector.getItems().size() > 1) {
            ySeriesSelector.setValue(ySeriesSelector.getItems().get(1));
        }
        inputSelectorFile = selectedFile;
        updateWorkingCoordinateSelectors();
    }

    private static @Nullable CoordinatePair getInputCoordinatePair(@Nullable ColumnSchema schema) {
        if (schema == null) {
            return null;
        }
        SpatialReference reference = schema.getSpatialReference();
        if (reference != null && reference.activePair() != null) {
            return reference.activePair();
        }
        if (reference != null && reference.geographicPair() != null) {
            return reference.geographicPair();
        }
        String longitude = schema.getHeaderBySemantic(Semantic.LONGITUDE.getName());
        String latitude = schema.getHeaderBySemantic(Semantic.LATITUDE.getName());
        return longitude != null && latitude != null
                ? new CoordinatePair(longitude, latitude, CoordinateReferenceService.WGS84_EPSG)
                : null;
    }

    private static @Nullable CrsOption getSuggestedUtmCrs(@Nullable SgyFile file, CoordinatePair pair) {
        if (file == null || pair.epsgCode() != CoordinateReferenceService.WGS84_EPSG) {
            return null;
        }
        List<Double> longitudes = new ArrayList<>();
        List<Double> latitudes = new ArrayList<>();
        for (GeoData value : Nulls.toEmpty(file.getGeoData())) {
            Number longitude = value.getNumber(pair.xSeries());
            Number latitude = value.getNumber(pair.ySeries());
            if (longitude == null || latitude == null || !Double.isFinite(longitude.doubleValue())
                    || !Double.isFinite(latitude.doubleValue())) {
                continue;
            }
            longitudes.add(longitude.doubleValue());
            latitudes.add(latitude.doubleValue());
        }
        if (longitudes.isEmpty()) {
            return null;
        }
        Collections.sort(longitudes);
        Collections.sort(latitudes);
        double longitude = longitudes.get(longitudes.size() / 2);
        double latitude = latitudes.get(latitudes.size() / 2);
        if (latitude < -80 || latitude > 84 || longitude < -180 || longitude > 180) {
            return null;
        }
        int zone = Math.clamp((int) Math.floor((longitude + 180) / 6) + 1, 1, 60);
        int epsg = (latitude >= 0 ? 32600 : 32700) + zone;
        return new CrsOption("WGS 84 / UTM zone %d%s (EPSG:%d)".formatted(zone, latitude >= 0 ? "N" : "S", epsg),
                Integer.toString(epsg));
    }

    private void updateOutputNames() {
        CrsOption target = targetCrsSelector.getValue();
        if (target == null) {
            return;
        }
        OutputNames names = outputNamesFor(CoordinateReferenceService.parseEpsg(target.epsg()));
        if (automaticOutputX == null || outputXInput.getText().trim().equals(automaticOutputX)
                || outputXInput.getText().trim().equals("X_CONVERTED")) {
            outputXInput.setText(names.x());
        }
        if (automaticOutputY == null || outputYInput.getText().trim().equals(automaticOutputY)
                || outputYInput.getText().trim().equals("Y_CONVERTED")) {
            outputYInput.setText(names.y());
        }
        automaticOutputX = names.x();
        automaticOutputY = names.y();
    }

    private static OutputNames outputNamesFor(int epsgCode) {
        if (epsgCode == CoordinateReferenceService.WGS84_EPSG) {
            return new OutputNames("LONGITUDE", "LATITUDE");
        }
        if (epsgCode >= 32601 && epsgCode <= 32660) {
            return new OutputNames("X_UTM%dN".formatted(epsgCode - 32600), "Y_UTM%dN".formatted(epsgCode - 32600));
        }
        if (epsgCode >= 32701 && epsgCode <= 32760) {
            return new OutputNames("X_UTM%dS".formatted(epsgCode - 32700), "Y_UTM%dS".formatted(epsgCode - 32700));
        }
        return new OutputNames("X_EPSG%d".formatted(epsgCode), "Y_EPSG%d".formatted(epsgCode));
    }

    private void updateWorkingCoordinateSelectors() {
        ColumnSchema schema = GeoData.getSchema(selectedFile != null ? selectedFile.getGeoData() : null);
        WorkingCoordinates working = schema != null ? schema.getWorkingCoordinates() : null;
        String defaultX = schema != null ? schema.getHeaderBySemantic(Semantic.LONGITUDE.getName()) : null;
        String defaultY = schema != null ? schema.getHeaderBySemantic(Semantic.LATITUDE.getName()) : null;
        MagneticSeriesSelector.update(workingXSelector, selectedFile,
                working != null ? working.xSeries() : defaultX);
        MagneticSeriesSelector.update(workingYSelector, selectedFile,
                working != null ? working.ySeries() : defaultY);
        if (working != null) {
            workingCrsSelector.setValue(findCrsOption(workingCrsSelector.getItems(),
                    Integer.toString(working.epsgCode())));
        }
    }

    private void updateWorkingCrsForSelection() {
        ColumnSchema schema = GeoData.getSchema(selectedFile != null ? selectedFile.getGeoData() : null);
        SpatialReference reference = schema != null ? schema.getSpatialReference() : null;
        String xSeries = workingXSelector.getValue();
        String ySeries = workingYSelector.getValue();
        if (reference == null || xSeries == null || ySeries == null) {
            return;
        }
        for (CoordinatePair pair : reference.coordinatePairs()) {
            if (pair.xSeries().equals(xSeries) && pair.ySeries().equals(ySeries)) {
                workingCrsSelector.setValue(findCrsOption(workingCrsSelector.getItems(),
                        Integer.toString(pair.epsgCode())));
                return;
            }
        }
    }

    private void validateInput() {
        String outputX = outputXInput.getText().trim();
        String outputY = outputYInput.getText().trim();
        ColumnSchema schema = GeoData.getSchema(selectedFile != null ? selectedFile.getGeoData() : null);
        boolean outputExists = schema != null && (schema.getColumn(outputX) != null || schema.getColumn(outputY) != null);
        boolean invalid = xSeriesSelector.getValue() == null || ySeriesSelector.getValue() == null
                || sourceCrsSelector.getValue() == null || targetCrsSelector.getValue() == null
                || outputX.isBlank() || outputY.isBlank() || outputX.equals(outputY) || outputExists;
        disableActions(invalid);
    }

    private void setWorkingCoordinates() {
        if (selectedFile == null || workingXSelector.getValue() == null || workingYSelector.getValue() == null
                || workingCrsSelector.getValue() == null) {
            return;
        }
        ColumnSchema schema = GeoData.getSchema(selectedFile.getGeoData());
        if (schema == null) {
            return;
        }
        CrsOption crs = workingCrsSelector.getValue();
        WorkingCoordinates working = new WorkingCoordinates(workingXSelector.getValue(), workingYSelector.getValue(),
                Integer.parseInt(crs.epsg()));
        schema.setWorkingCoordinates(working);
        ensureGeographicCoordinates(working, schema);
        model.publishEvent(new FileUpdatedEvent(this, selectedFile));
        updateWorkingCoordinatesLabel();
    }

    private void ensureGeographicCoordinates(WorkingCoordinates working, ColumnSchema schema) {
        String longitude = schema.getHeaderBySemantic(Semantic.LONGITUDE.getName());
        String latitude = schema.getHeaderBySemantic(Semantic.LATITUDE.getName());
        if (longitude != null && latitude != null) {
            registerGeographicPair(schema, longitude, latitude);
            return;
        }
        if (working.epsgCode() == CoordinateReferenceService.WGS84_EPSG) {
            schema.getColumn(working.xSeries()).setSemantic(Semantic.LONGITUDE.getName());
            schema.getColumn(working.ySeries()).setSemantic(Semantic.LATITUDE.getName());
            registerGeographicPair(schema, working.xSeries(), working.ySeries());
            return;
        }
        if (!(model.getChart(selectedFile) instanceof SensorLineChart chart)) {
            return;
        }
        String outputX = availableSeriesName(schema, "LONGITUDE_WGS84");
        String outputY = availableSeriesName(schema, "LATITUDE_WGS84");
        submitAction(() -> {
            try {
                CoordinateConversionService.CoordinateConversionResult result = conversionService.convert(
                        chart.getFile().getGeoData(), working.xSeries(), working.ySeries(),
                        Integer.toString(working.epsgCode()), "4326");
                chart.createDerivedSeries(outputX, result.xValues());
                chart.createDerivedSeries(outputY, result.yValues());
                ColumnSchema updated = GeoData.getSchema(chart.getFile().getGeoData());
                if (updated != null) {
                    updated.getColumn(outputX).setSemantic(Semantic.LONGITUDE.getName());
                    updated.getColumn(outputY).setSemantic(Semantic.LATITUDE.getName());
                    registerGeographicPair(updated, outputX, outputY);
                    model.publishEvent(new FileUpdatedEvent(this, chart.getFile()));
                }
            } catch (IllegalArgumentException e) {
                Platform.runLater(() -> Dialogs.showError("Coordinate conversion", e.getMessage()));
            }
            return null;
        });
    }

    private static String availableSeriesName(ColumnSchema schema, String baseName) {
        String name = baseName;
        int suffix = 2;
        while (schema.getColumn(name) != null) {
            name = baseName + "_" + suffix++;
        }
        return name;
    }

    private static void registerGeographicPair(ColumnSchema schema, String longitude, String latitude) {
        CoordinatePair pair = new CoordinatePair(longitude, latitude, CoordinateReferenceService.WGS84_EPSG);
        SpatialReference reference = schema.getSpatialReference();
        schema.setSpatialReference(reference != null
                ? reference.withGeographicPair(pair)
                : new SpatialReference(List.of(pair), pair, pair));
    }

    private void updateWorkingCoordinatesLabel() {
        ColumnSchema schema = GeoData.getSchema(selectedFile != null ? selectedFile.getGeoData() : null);
        WorkingCoordinates working = schema != null ? schema.getWorkingCoordinates() : null;
        workingCoordinatesLabel.setText(working == null
                ? "Spatial processing currently uses the mapped latitude and longitude."
                : "Spatial processing coordinates: %s / %s (%s)".formatted(working.xSeries(), working.ySeries(),
                findCrsOption(workingCrsSelector.getItems(), Integer.toString(working.epsgCode())).displayName()));
    }

    @Override
    protected void onApply(ActionEvent event) {
        if (!(model.getChart(selectedFile) instanceof SensorLineChart chart)) {
            return;
        }
        String inputX = xSeriesSelector.getValue();
        String inputY = ySeriesSelector.getValue();
        if (inputX == null || inputY == null) {
            return;
        }
        String outputX = outputXInput.getText().trim();
        String outputY = outputYInput.getText().trim();
        String sourceCrs = sourceCrsSelector.getValue().epsg();
        String targetCrs = targetCrsSelector.getValue().epsg();
        submitAction(() -> {
            try {
                CoordinateConversionService.CoordinateConversionResult result = conversionService.convert(
                        chart.getFile().getGeoData(), inputX, inputY, sourceCrs, targetCrs);
                chart.createDerivedSeries(outputX, result.xValues());
                chart.createDerivedSeries(outputY, result.yValues());
                ColumnSchema schema = GeoData.getSchema(chart.getFile().getGeoData());
                if (schema != null) {
                    int targetCode = CoordinateReferenceService.parseEpsg(targetCrs);
                    if (targetCode == CoordinateReferenceService.WGS84_EPSG) {
                        schema.getColumn(outputX).setSemantic(Semantic.LONGITUDE.getName());
                        schema.getColumn(outputY).setSemantic(Semantic.LATITUDE.getName());
                    }
                    CoordinatePair pair = new CoordinatePair(outputX, outputY,
                            targetCode);
                    SpatialReference reference = schema.getSpatialReference();
                    if (reference == null) {
                        schema.setSpatialReference(new SpatialReference(List.of(pair), pair,
                                targetCode == CoordinateReferenceService.WGS84_EPSG ? pair : null));
                    } else if (targetCode == CoordinateReferenceService.WGS84_EPSG) {
                        schema.setSpatialReference(reference.withGeographicPair(pair));
                    } else {
                        schema.setSpatialReference(reference.withCoordinatePair(pair));
                    }
                }
            } catch (IllegalArgumentException e) {
                Platform.runLater(() -> Dialogs.showError("Coordinate conversion", e.getMessage()));
            }
            return null;
        });
    }

    @EventListener
    private void onFileSelected(FileSelectedEvent event) {
        Platform.runLater(() -> selectFile(event.getFile()));
    }

    @EventListener
    private void onSeriesUpdated(SeriesUpdatedEvent event) {
        if (event.getFile().equals(selectedFile)) {
            Platform.runLater(this::updateView);
        }
    }

    private record CrsOption(String displayName, String epsg) {

        @Override
        public String toString() {
            return displayName;
        }
    }

    private record OutputNames(String x, String y) {
    }
}
