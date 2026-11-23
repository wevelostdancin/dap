package com.dap.nativeengine.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Encoding decision for a CSV source. DuckDB's {@code read_csv} accepts only utf-8, utf-16 and
 * latin-1, so legacy single-byte Cyrillic files are decoded and rewritten as UTF-8 first.
 */
public final class CsvEncoding {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int SAMPLE_BYTES = 256 * 1024;
    /** Detection order matters: ties keep the first entry, so windows-1251 wins. */
    private static final Charset[] LEGACY = {
            Charset.forName("windows-1251"),
            Charset.forName("cp866"),
            Charset.forName("koi8-r")
    };

    private CsvEncoding() {
    }

    /**
     * The decision for one source file.
     *
     * @param requested the caller's value, or {@code auto} when it was null or blank
     * @param resolved canonical lower-case name of the chosen charset
     * @param charset the charset to decode the source with
     * @param transcode whether the source must be rewritten as UTF-8
     * @param duckdbEncoding the {@code encoding} option to pass to DuckDB
     */
    public record Plan(String requested, String resolved, Charset charset, boolean transcode,
                       String duckdbEncoding) {
        /** True when the caller did not choose an encoding and detection picked one. */
        public boolean auto() {
            return isAuto(requested);
        }
    }

    /**
     * Resolves how {@code source} should be read. An automatic request inspects the BOM, then
     * validates UTF-8 with a strict decoder, then scores the first 256 KiB against the common
     * single-byte Cyrillic charsets. An explicit request accepts any supported Java charset;
     * only UTF-8, UTF-16 and latin-1 can be handed to DuckDB without transcoding.
     *
     * @param source the CSV file
     * @param requested caller-supplied encoding, may be null, blank or {@code auto}
     * @return the plan for this file
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if an explicit encoding is not supported
     */
    public static Plan plan(Path source, String requested) throws IOException {
        String normalized = requested == null ? "" : requested.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "auto".equals(normalized)) {
            String label = requested == null || requested.isBlank() ? "auto" : requested;
            return detect(source, label);
        }
        return explicit(requested, normalized);
    }

    /**
     * Streams {@code source} through {@code charset} into UTF-8 at {@code target}, replacing any
     * previous content. No BOM is written: DuckDB reads the output as plain UTF-8.
     *
     * @param source the file to decode
     * @param target the UTF-8 file to write
     * @param charset the charset of {@code source}
     * @throws IOException if either file cannot be read or written
     */
    public static void transcode(Path source, Path target, Charset charset) throws IOException {
        transcode(source, target, charset, true);
    }

    /**
     * Streams {@code source} through {@code charset} into UTF-8 at {@code target}.
     *
     * @param lenient {@code true} replaces undecodable bytes with U+FFFD (detected encodings,
     *                where the goal is to let DuckDB report the parse error); {@code false}
     *                throws {@link java.nio.charset.CharacterCodingException} so an explicit
     *                wrong encoding fails loudly instead of silently corrupting values
     */
    public static void transcode(Path source, Path target, Charset charset, boolean lenient)
            throws IOException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(lenient ? CodingErrorAction.REPLACE : CodingErrorAction.REPORT)
                .onUnmappableCharacter(lenient ? CodingErrorAction.REPLACE : CodingErrorAction.REPORT);
        try (Reader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(source), decoder));
             Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            char[] buffer = new char[BUFFER_BYTES / 2];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, read);
            }
        }
    }

    /**
     * Human-readable summary of a plan: {@code "<resolved> (auto)"} when the caller did not
     * choose an encoding explicitly, otherwise just {@code resolved}.
     *
     * @param plan the plan to describe
     * @return the description
     */
    public static String describe(Plan plan) {
        return isAuto(plan.requested()) ? plan.resolved() + " (auto)" : plan.resolved();
    }

    private static Plan detect(Path source, String requested) throws IOException {
        String bom = bomEncoding(source);
        if (bom != null) {
            Charset charset = "utf-8".equals(bom) ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16;
            return new Plan(requested, bom, charset, false, bom);
        }
        if (isStrictUtf8(source)) {
            return new Plan(requested, "utf-8", StandardCharsets.UTF_8, false, "utf-8");
        }
        byte[] sample = sample(source);
        rejectBinary(sample, source);
        Charset charset = bestLegacyCharset(sample);
        return new Plan(requested, charset.name().toLowerCase(Locale.ROOT), charset, true, "utf-8");
    }

    private static String bomEncoding(Path source) throws IOException {
        byte[] head = new byte[3];
        int read;
        try (InputStream in = Files.newInputStream(source)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return "utf-8";
        }
        if (read >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return "utf-16";
        }
        if (read >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return "utf-16";
        }
        return null;
    }

    private static boolean isStrictUtf8(Path source) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (InputStream in = Files.newInputStream(source);
             Reader reader = new InputStreamReader(in, decoder)) {
            char[] buffer = new char[BUFFER_BYTES / 2];
            while (reader.read(buffer) != -1) {
                // A strict decoder throws on malformed input before returning any such text.
            }
            return true;
        } catch (CharacterCodingException invalid) {
            return false;
        }
    }

    private static byte[] sample(Path source) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            return in.readNBytes(SAMPLE_BYTES);
        }
    }

    /**
     * Reject a workbook or archive passed as CSV before the decoder turns it into mojibake.
     * A text file may contain a stray control byte; NUL bytes or a tenth of the sample being
     * control characters is binary content, not CSV.
     */
    private static void rejectBinary(byte[] sample, Path source) {
        if (sample.length == 0) {
            return;
        }
        int nul = 0;
        int controls = 0;
        for (byte value : sample) {
            int current = value & 0xFF;
            if (current == 0) {
                nul++;
            } else if (current < 0x20 && current != '\t' && current != '\n' && current != '\r') {
                controls++;
            }
        }
        if (nul * 100 > sample.length || controls * 10 > sample.length) {
            throw new IllegalArgumentException("cannot read " + source + " as CSV: binary content "
                    + "detected; convert the file to CSV or pass the matching format");
        }
    }

    private static Charset bestLegacyCharset(byte[] sample) {
        Charset best = LEGACY[0];
        double bestScore = score(sample, best);
        for (int index = 1; index < LEGACY.length; index++) {
            double candidate = score(sample, LEGACY[index]);
            if (candidate > bestScore) {
                best = LEGACY[index];
                bestScore = candidate;
            }
        }
        return best;
    }

    /** Cyrillic-letter share among non-blank characters, minus one point per stray control. */
    private static double score(byte[] sample, Charset charset) {
        String text = new String(sample, charset);
        int letters = 0;
        int controls = 0;
        int nonBlank = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                continue;
            }
            nonBlank++;
            if (Character.isLetter(current)
                    && Character.UnicodeBlock.of(current) == Character.UnicodeBlock.CYRILLIC) {
                letters++;
            }
            if (Character.isISOControl(current)) {
                controls++;
            }
        }
        return nonBlank == 0 ? 0.0 : (double) (letters - controls) / nonBlank;
    }

    private static Plan explicit(String requested, String normalized) {
        Charset charset;
        String duckdb;
        boolean transcode;
        switch (normalized) {
            case "utf8", "utf-8" -> {
                charset = StandardCharsets.UTF_8;
                duckdb = "utf-8";
                transcode = false;
            }
            case "utf16", "utf-16" -> {
                charset = StandardCharsets.UTF_16;
                duckdb = "utf-16";
                transcode = false;
            }
            case "latin1", "latin-1", "iso-8859-1", "iso8859-1" -> {
                charset = StandardCharsets.ISO_8859_1;
                duckdb = "latin-1";
                transcode = false;
            }
            case "windows-1251", "cp1251", "win1251", "cp-1251" -> {
                charset = Charset.forName("windows-1251");
                duckdb = "utf-8";
                transcode = true;
            }
            default -> {
                Charset named = null;
                try {
                    if (Charset.isSupported(normalized)) {
                        named = Charset.forName(normalized);
                    }
                } catch (IllegalArgumentException ignored) {
                    // An illegal charset name is no more usable than an unknown one.
                }
                if (named == null) {
                    throw new IllegalArgumentException("unsupported CSV encoding '" + requested
                            + "'; use auto, utf-8, utf-16, latin-1, cp1251, cp866, koi8-r "
                            + "or a Java charset name");
                }
                charset = named;
                duckdb = "utf-8";
                transcode = true;
            }
        }
        return new Plan(requested, charset.name().toLowerCase(Locale.ROOT), charset, transcode, duckdb);
    }

    private static boolean isAuto(String requested) {
        return requested == null || requested.isBlank()
                || "auto".equals(requested.strip().toLowerCase(Locale.ROOT));
    }
}
