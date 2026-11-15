package com.dap.nativeengine.report;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java2D text measurement for the paginated writers: wraps a line to a width and reports
 * ascent and line height in points. The layout is computed on a scratch image with
 * antialiasing off, so metrics do not depend on a display; nothing here installs fonts, the
 * PDF writer only uses the standard Helvetica faces.
 */
public final class TextLayout implements AutoCloseable {
    private final BufferedImage scratch;
    private final Graphics2D graphics;
    private final Map<Font, FontMetrics> cache = new HashMap<>();

    public TextLayout() {
        scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        graphics = scratch.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    }

    public double width(String value, Font font) {
        return metrics(font).stringWidth(value == null ? "" : value);
    }

    public double ascent(Font font) {
        return metrics(font).getAscent();
    }

    public double lineHeight(Font font) {
        return metrics(font).getHeight();
    }

    /**
     * Greedy word wrap. An explicit empty line stays an empty line; a word wider than the
     * available width is split by characters so a long token cannot overflow the page.
     */
    public List<String> wrap(String value, Font font, double maxWidth) {
        FontMetrics metrics = metrics(font);
        List<String> lines = new ArrayList<>();
        String text = value == null ? "" : value;
        if (text.isEmpty()) {
            lines.add("");
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (metrics.stringWidth(word) <= maxWidth) {
                line.append(word);
                continue;
            }
            StringBuilder chunk = new StringBuilder();
            for (int index = 0; index < word.length(); index++) {
                char character = word.charAt(index);
                if (chunk.length() > 0
                        && metrics.stringWidth(chunk.toString() + character) > maxWidth) {
                    lines.add(chunk.toString());
                    chunk.setLength(0);
                }
                chunk.append(character);
            }
            line.append(chunk);
        }
        if (line.length() > 0 || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private FontMetrics metrics(Font font) {
        FontMetrics metrics = cache.get(font);
        if (metrics == null) {
            metrics = graphics.getFontMetrics(font);
            cache.put(font, metrics);
        }
        return metrics;
    }

    @Override
    public void close() {
        graphics.dispose();
    }
}
