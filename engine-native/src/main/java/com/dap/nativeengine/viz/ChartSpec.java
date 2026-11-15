package com.dap.nativeengine.viz;

import java.util.List;

/**
 * Everything the renderer needs to draw one chart: the selected columns, the look of the
 * canvas and the chart-type options. Data itself is passed separately as a table preview.
 */
public record ChartSpec(
        String type,
        String x,
        List<String> y,
        String series,
        String title,
        String subtitle,
        String xLabel,
        String yLabel,
        String xUnit,
        String yUnit,
        int width,
        int height,
        int bins,
        int limit,
        SortMode sort,
        boolean descending,
        boolean logX,
        boolean logY,
        boolean grid,
        boolean dataLabels,
        List<Annotation> annotations,
        String facet,
        int facetColumns,
        String lat,
        String lng,
        String size,
        String color,
        String label) {

    public ChartSpec {
        y = List.copyOf(y);
        annotations = List.copyOf(annotations);
    }

    /** Category order for bar, pie, boxplot and heatmap axes. */
    public enum SortMode {
        /** Sort by the aggregated value (bars: sum, pie: share, boxplot: median). */
        VALUE,
        /** Sort by the category label (numeric labels compare as numbers). */
        LABEL,
        /** Keep the order in which categories first appear in the input. */
        NONE
    }

    /** Text note placed on the canvas, optionally anchored to a data point or category. */
    public record Annotation(String text, String category, Double x, Double y, String color) {
    }

    public boolean hasTitle() {
        return title != null && !title.isBlank();
    }

    public boolean hasSubtitle() {
        return subtitle != null && !subtitle.isBlank();
    }

    public boolean faceted() {
        return facet != null && !facet.isBlank();
    }
}
