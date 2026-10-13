package com.ugcs.geohammer.chart.tool;

import com.ugcs.geohammer.chart.csv.SensorLineChart;
import com.ugcs.geohammer.format.GeoData;
import com.ugcs.geohammer.format.SgyFile;
import com.ugcs.geohammer.format.csv.CsvFile;
import com.ugcs.geohammer.model.ColumnSchema;
import com.ugcs.geohammer.model.Model;
import com.ugcs.geohammer.model.event.FileSelectedEvent;
import com.ugcs.geohammer.model.event.SeriesUpdatedEvent;
import com.ugcs.geohammer.service.math.SeriesMathService;
import com.ugcs.geohammer.view.Dialogs;
import com.ugcs.geohammer.view.control.InputWithTopLabel;
import com.ugcs.geohammer.view.control.NodeWithTopLabel;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

@Component
public class SeriesMathTool extends FilterToolView {

    private final Model model;

    private final SeriesMathService seriesMathService;

    private final ComboBox<String> seriesSelector;

    private final TextArea expressionInput;

    private final TextField outputSeriesInput;

    public SeriesMathTool(Model model, SeriesMathService seriesMathService, ExecutorService executor) {
        super(executor);
        this.model = model;
        this.seriesMathService = seriesMathService;

        seriesSelector = new ComboBox<>();
        seriesSelector.setMaxWidth(Double.MAX_VALUE);

        Button insertSeriesButton = new Button("Insert selected series");
        insertSeriesButton.setOnAction(event -> insertSelectedSeries());

        expressionInput = new TextArea();
        expressionInput.setPromptText("For example: {RAWMAG} - {BASEMAG1}");
        expressionInput.setPrefRowCount(3);
        expressionInput.setWrapText(true);
        expressionInput.textProperty().addListener((observable, oldValue, newValue) -> validateInput());

        InputWithTopLabel outputSeries = new InputWithTopLabel("New series name");
        outputSeriesInput = outputSeries.getInput();
        outputSeriesInput.textProperty().addListener((observable, oldValue, newValue) -> validateInput());

        inputContainer.getChildren().setAll(
                new NodeWithTopLabel<>("Available series", seriesSelector),
                insertSeriesButton,
                new NodeWithTopLabel<>("Expression", expressionInput),
                new Label("Use {Series name}. Operators: +, -, *, /, ^; functions: abs, sqrt, min, max, log, log10, sin, cos, tan."),
                outputSeries
        );
        showApply(true);
    }

    @Override
    public boolean isVisibleFor(SgyFile file) {
        return file instanceof CsvFile;
    }

    @Override
    public void updateView() {
        String selectedSeries = model.getSelectedSeriesName(selectedFile);
        MagneticSeriesSelector.update(seriesSelector, selectedFile, selectedSeries);
        validateInput();
    }

    private void insertSelectedSeries() {
        String series = seriesSelector.getValue();
        if (series == null) {
            return;
        }
        expressionInput.insertText(expressionInput.getCaretPosition(), "{" + series + "}");
        expressionInput.requestFocus();
    }

    private void validateInput() {
        String outputSeries = outputSeriesInput.getText().trim();
        ColumnSchema schema = GeoData.getSchema(selectedFile != null ? selectedFile.getGeoData() : null);
        boolean existingSeries = schema != null && schema.getColumn(outputSeries) != null;
        boolean invalid = outputSeries.isBlank() || expressionInput.getText().isBlank() || existingSeries;
        disableActions(invalid);
    }

    @Override
    protected void onApply(ActionEvent event) {
        if (!(model.getChart(selectedFile) instanceof SensorLineChart chart)) {
            return;
        }
        String expression = expressionInput.getText().trim();
        String outputSeries = outputSeriesInput.getText().trim();
        submitAction(() -> {
            try {
                SeriesMathService.SeriesMathResult result = seriesMathService.evaluate(chart.getFile().getGeoData(), expression);
                chart.createDerivedSeries(outputSeries, result.sourceSeries(), result.values());
            } catch (IllegalArgumentException e) {
                Platform.runLater(() -> Dialogs.showError("Series math", e.getMessage()));
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
}
