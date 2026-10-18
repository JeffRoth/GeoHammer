package com.ugcs.geohammer.chart.tool;

import com.ugcs.geohammer.Settings;
import com.ugcs.geohammer.chart.csv.SensorLineChart;
import com.ugcs.geohammer.format.SgyFile;
import com.ugcs.geohammer.format.csv.CsvFile;
import com.ugcs.geohammer.model.Model;
import com.ugcs.geohammer.model.event.FileSelectedEvent;
import com.ugcs.geohammer.service.magnetics.GnssLagEstimate;
import com.ugcs.geohammer.service.magnetics.GnssLagEstimationService;
import com.ugcs.geohammer.util.Strings;
import com.ugcs.geohammer.util.Templates;
import com.ugcs.geohammer.view.control.InputWithTopLabel;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

@Component
public class TimeLagTool extends FilterToolView {

    private final Model model;

    private final Settings settings;

    private final TextField shiftInput;

    private final ComboBox<String> seriesSelector;

    private final Label diagnostics;

    private final TextField outputSeriesInput;

    public TimeLagTool(Model model, Settings settings, GnssLagEstimationService lagEstimationService,
                       ExecutorService executor) {
        super(executor);

        this.model = model;
        this.settings = settings;

        InputWithTopLabel shiftWithLabel = new InputWithTopLabel("Shift (measurements)");
        shiftInput = shiftWithLabel.getInput();
        shiftInput.textProperty().addListener(this::onShiftChange);

        InputWithTopLabel outputSeries = new InputWithTopLabel("Lag-corrected series");
        outputSeriesInput = outputSeries.getInput();
        outputSeriesInput.textProperty().addListener((observable, oldValue, newValue) -> validateInput());
        seriesSelector = new ComboBox<>();
        seriesSelector.setMaxWidth(Double.MAX_VALUE);
        seriesSelector.setOnAction(event -> {
            if (seriesSelector.getValue() != null) {
                outputSeriesInput.setText(seriesSelector.getValue() + "_LAG");
            }
            validateInput();
        });
        Button estimateButton = new Button("Estimate from reciprocal lines");
        estimateButton.setMaxWidth(Double.MAX_VALUE);
        diagnostics = new Label("Estimates a measurement shift from nearby reciprocal lines.");
        estimateButton.setOnAction(event -> estimateLag(lagEstimationService));
        inputContainer.getChildren().setAll(
                new com.ugcs.geohammer.view.control.NodeWithTopLabel<>("Magnetic series", seriesSelector),
                estimateButton, diagnostics, outputSeries, shiftWithLabel);

        showApply(true);
        showApplyToAll(false);
    }

    @Override
    public boolean isVisibleFor(SgyFile file) {
        return file instanceof CsvFile;
    }

    private void onShiftChange(ObservableValue<? extends String> observable, String oldValue, String newValue) {
        Integer shift = null;
        if (newValue != null) {
            try {
                shift = Integer.parseInt(newValue);
            } catch (NumberFormatException e) {
                // null
            }
        }
        shiftInput.setUserData(shift);
        validateInput();
    }

    public void validateInput() {
        Integer shift = (Integer) shiftInput.getUserData();
        boolean disable = shift == null || Math.abs(shift) > 10000 || seriesSelector.getValue() == null
                || outputSeriesInput.getText().isBlank() || outputSeriesInput.getText().equals(seriesSelector.getValue());
        disableActions(disable);
    }

    @Override
    public void updateView() {
        String selected = model.getChart(selectedFile) instanceof SensorLineChart chart ? chart.getSelectedSeriesName() : null;
        MagneticSeriesSelector.update(seriesSelector, selectedFile, selected);
        validateInput();
    }

    private void estimateLag(GnssLagEstimationService lagEstimationService) {
        if (!(model.getChart(selectedFile) instanceof SensorLineChart chart) || seriesSelector.getValue() == null) {
            return;
        }
        submitAction(() -> {
            try {
                GnssLagEstimate estimate = lagEstimationService.estimate(chart.getFile().getGeoData(), seriesSelector.getValue());
                Platform.runLater(() -> {
                    shiftInput.setText(Integer.toString(estimate.shiftMeasurements()));
                    diagnostics.setText("Estimated shift: %d measurements (%.2f m, correlation %.2f, %d pairs)"
                            .formatted(estimate.shiftMeasurements(), estimate.approximateDistanceMeters(),
                                    estimate.correlation(), estimate.linePairCount()));
                });
            } catch (IllegalArgumentException e) {
                Platform.runLater(() -> diagnostics.setText(e.getMessage()));
            }
            return null;
        });
    }

    @Override
    public void loadPreferences() {
        String templateName = Templates.getTemplateName(selectedFile);
        if (!Strings.isNullOrEmpty(templateName)) {
            shiftInput.setText(settings.getStringOrDefault(
                    "timelag", templateName, Strings.empty()));
        }
    }

    @Override
    public void savePreferences() {
        String templateName = Templates.getTemplateName(selectedFile);
        if (!Strings.isNullOrEmpty(templateName)) {
            settings.setValue("timelag", templateName, shiftInput.getText());
        }
    }

    @Override
    protected void onApply(ActionEvent event) {
        Integer shift = (Integer) shiftInput.getUserData();
        if (shift == null || seriesSelector.getValue() == null || outputSeriesInput.getText().isBlank()) {
            return;
        }
        if (model.getChart(selectedFile) instanceof SensorLineChart sensorChart) {
            submitAction(() -> {
                sensorChart.createTimeLagSeries(seriesSelector.getValue(), outputSeriesInput.getText().trim(), shift);
                return null;
            });
        }
    }

    @Override
    protected void onApplyToAll(ActionEvent event) {
        Integer shift = (Integer) shiftInput.getUserData();
        if (shift == null || seriesSelector.getValue() == null || outputSeriesInput.getText().isBlank()) {
            return;
        }
        if (model.getChart(selectedFile) instanceof SensorLineChart sensorChart) {
            String seriesName = seriesSelector.getValue();
            submitAction(() -> {
                model.getSensorCharts().stream()
                        .filter(c -> Templates.equals(c.getFile(), selectedFile))
                        .forEach(c -> c.applyTimeLag(seriesName, shift));
                return null;
            });
        }
    }

    @EventListener
    private void onFileSelected(FileSelectedEvent event) {
        Platform.runLater(() -> selectFile(event.getFile()));
    }
}
