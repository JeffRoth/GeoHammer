package com.ugcs.geohammer.chart.tool;

import com.ugcs.geohammer.Settings;
import com.ugcs.geohammer.chart.csv.SensorLineChart;
import com.ugcs.geohammer.format.SgyFile;
import com.ugcs.geohammer.format.csv.CsvFile;
import com.ugcs.geohammer.model.Model;
import com.ugcs.geohammer.model.event.FileSelectedEvent;
import com.ugcs.geohammer.model.event.SeriesUpdatedEvent;
import com.ugcs.geohammer.util.Strings;
import com.ugcs.geohammer.util.Templates;
import com.ugcs.geohammer.view.control.InputWithTopLabel;
import com.ugcs.geohammer.view.control.NodeWithTopLabel;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

@Component
public class LowPassTool extends FilterToolView {

    private final Model model;

    private final Settings settings;

    private final TextField orderInput;

    private final ComboBox<String> inputSeriesSelector;

    private final TextField outputSeriesInput;

    public LowPassTool(Model model, Settings settings, ExecutorService executor) {
        super(executor);

        this.model = model;
        this.settings = settings;

        InputWithTopLabel orderWithLabel = new InputWithTopLabel("Filter length (measurements)");
        orderInput = orderWithLabel.getInput();
        orderInput.textProperty().addListener(this::onOrderChange);
        InputWithTopLabel outputWithLabel = new InputWithTopLabel("Filtered output series");
        outputSeriesInput = outputWithLabel.getInput();
        outputSeriesInput.textProperty().addListener((observable, oldValue, newValue) -> validateInput());
        inputSeriesSelector = new ComboBox<>();
        inputSeriesSelector.setMaxWidth(Double.MAX_VALUE);
        inputSeriesSelector.setOnAction(event -> {
            if (inputSeriesSelector.getValue() != null) {
                outputSeriesInput.setText(inputSeriesSelector.getValue() + "_LPF");
            }
            validateInput();
        });

        inputContainer.getChildren().setAll(
                new NodeWithTopLabel<>("Input series", inputSeriesSelector),
                orderWithLabel,
                outputWithLabel
        );

        showApply(true);
        showApplyToAll(false);
    }

    @Override
    public boolean isVisibleFor(SgyFile file) {
        return file instanceof CsvFile;
    }

    @Override
    public void updateView() {
        String selected = model.getChart(selectedFile) instanceof SensorLineChart chart ? chart.getSelectedSeriesName() : null;
        MagneticSeriesSelector.update(inputSeriesSelector, selectedFile, selected);
        if (selected != null && outputSeriesInput.getText().isBlank()) {
            outputSeriesInput.setText(selected + "_LPF");
        }
        validateInput();
    }

    private void onOrderChange(ObservableValue<? extends String> observable, String oldValue, String newValue) {
        Integer order = null;
        if (newValue != null) {
            try {
                order = Integer.parseInt(newValue);
            } catch (NumberFormatException e) {
                // null
            }
        }
        orderInput.setUserData(order);
        validateInput();
    }

    public void validateInput() {
        Integer order = (Integer) orderInput.getUserData();
        boolean disable = order == null || order < 0 || order > 10000 || inputSeriesSelector.getValue() == null
                || outputSeriesInput.getText().isBlank() || outputSeriesInput.getText().trim().equals(inputSeriesSelector.getValue());
        disableActions(disable);
    }

    @Override
    public void loadPreferences() {
        String templateName = Templates.getTemplateName(selectedFile);
        if (!Strings.isNullOrEmpty(templateName)) {
            orderInput.setText(settings.getStringOrDefault(
                    "lowpass", templateName, Strings.empty()));
        }
    }

    @Override
    public void savePreferences() {
        String templateName = Templates.getTemplateName(selectedFile);
        if (!Strings.isNullOrEmpty(templateName)) {
            settings.setValue("lowpass", templateName, orderInput.getText());
        }
    }

    @Override
    protected void onApply(ActionEvent event) {
        Integer order = (Integer)orderInput.getUserData();
        if (order == null) {
            return;
        }
        if (model.getChart(selectedFile) instanceof SensorLineChart sensorChart) {
            String inputSeries = inputSeriesSelector.getValue();
            String outputSeries = outputSeriesInput.getText().trim();
            if (inputSeries == null) return;
            submitAction(() -> {
                sensorChart.createLowPassSeries(inputSeries, outputSeries, order);
                return null;
            });
        }
    }

    @EventListener
    private void onFileSelected(FileSelectedEvent event) {
        Platform.runLater(() -> selectFile(event.getFile()));
    }

    @EventListener
    private void onSeriesUpdated(SeriesUpdatedEvent event) {
        if (event.getFile().equals(selectedFile)) {
            Platform.runLater(() -> {
                MagneticSeriesSelector.update(inputSeriesSelector, selectedFile, event.getSeriesName());
                validateInput();
            });
        }
    }
}
