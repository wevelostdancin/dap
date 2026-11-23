package com.dap.nativeengine.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Encoding detection and transcoding for CSV ingest. */
class CsvEncodingTest {
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    private static final String CYRILLIC = "Привет, мир! Год 2026.";

    @TempDir
    Path dir;

    @Test
    void detectsWindows1251Cyrillic() throws Exception {
        Path source = dir.resolve("cp1251.csv");
        Files.write(source, CYRILLIC.getBytes(WINDOWS_1251));

        CsvEncoding.Plan plan = CsvEncoding.plan(source, "auto");

        assertEquals("windows-1251", plan.resolved());
        assertEquals(WINDOWS_1251, plan.charset());
        assertTrue(plan.transcode());
        assertEquals("utf-8", plan.duckdbEncoding());
    }

    @Test
    void detectsPlainUtf8() throws Exception {
        Path source = dir.resolve("utf8.csv");
        Files.writeString(source, CYRILLIC, StandardCharsets.UTF_8);

        CsvEncoding.Plan plan = CsvEncoding.plan(source, null);

        assertEquals("utf-8", plan.resolved());
        assertFalse(plan.transcode());
        assertEquals("utf-8", plan.duckdbEncoding());
    }

    @Test
    void detectsUtf8Bom() throws Exception {
        Path source = dir.resolve("utf8-bom.csv");
        Files.write(source, withBom(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                CYRILLIC.getBytes(StandardCharsets.UTF_8)));

        CsvEncoding.Plan plan = CsvEncoding.plan(source, "auto");

        assertEquals("utf-8", plan.resolved());
        assertFalse(plan.transcode());
        assertEquals("utf-8", plan.duckdbEncoding());
    }

    @Test
    void detectsUtf16LeBom() throws Exception {
        Path source = dir.resolve("utf16le.csv");
        Files.write(source, withBom(new byte[]{(byte) 0xFF, (byte) 0xFE},
                CYRILLIC.getBytes(StandardCharsets.UTF_16LE)));

        CsvEncoding.Plan plan = CsvEncoding.plan(source, "auto");

        assertEquals("utf-16", plan.resolved());
        assertFalse(plan.transcode());
        assertEquals("utf-16", plan.duckdbEncoding());
    }

    @Test
    void explicitLatin1IsNotTranscoded() throws Exception {
        Path source = dir.resolve("latin1.csv");
        Files.writeString(source, "café", StandardCharsets.ISO_8859_1);

        CsvEncoding.Plan plan = CsvEncoding.plan(source, "latin-1");

        assertEquals("iso-8859-1", plan.resolved());
        assertEquals(StandardCharsets.ISO_8859_1, plan.charset());
        assertFalse(plan.transcode());
        assertEquals("latin-1", plan.duckdbEncoding());
    }

    @Test
    void explicitCp1251IsTranscoded() throws Exception {
        Path source = dir.resolve("explicit-cp1251.csv");
        Files.write(source, CYRILLIC.getBytes(WINDOWS_1251));

        CsvEncoding.Plan plan = CsvEncoding.plan(source, "cp1251");

        assertEquals("windows-1251", plan.resolved());
        assertTrue(plan.transcode());
        assertEquals("utf-8", plan.duckdbEncoding());
        assertEquals("cp1251", plan.requested());
    }

    @Test
    void rejectsUnknownEncoding() throws Exception {
        Path source = dir.resolve("unknown.csv");
        Files.writeString(source, "a,b\n1,2\n", StandardCharsets.UTF_8);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CsvEncoding.plan(source, "no-such-encoding"));

        assertTrue(failure.getMessage().contains("unsupported CSV encoding"), failure.getMessage());
    }

    @Test
    void transcodesWindows1251ToUtf8KeepingCrLf() throws Exception {
        String text = "Привет\r\nМир\r\n";
        Path source = dir.resolve("in.csv");
        Path target = dir.resolve("out.csv");
        Files.write(source, text.getBytes(WINDOWS_1251));

        CsvEncoding.transcode(source, target, WINDOWS_1251);

        byte[] output = Files.readAllBytes(target);
        assertEquals(text, new String(output, StandardCharsets.UTF_8));
        int lines = 0;
        for (int index = 0; index + 1 < output.length; index++) {
            if (output[index] == '\r' && output[index + 1] == '\n') {
                lines++;
            }
        }
        assertEquals(2, lines);
        assertFalse(output.length >= 3 && (output[0] & 0xFF) == 0xEF
                && (output[1] & 0xFF) == 0xBB && (output[2] & 0xFF) == 0xBF);
    }

    @Test
    void emptyFileIsUtf8() throws Exception {
        Path source = dir.resolve("empty.csv");
        Files.write(source, new byte[0]);

        CsvEncoding.Plan plan = CsvEncoding.plan(source, "auto");

        assertEquals("utf-8", plan.resolved());
        assertFalse(plan.transcode());
    }

    @Test
    void describeMarksAutomaticDetection() throws Exception {
        Path source = dir.resolve("described.csv");
        Files.writeString(source, "plain,ascii\n1,2\n", StandardCharsets.UTF_8);

        String automatic = CsvEncoding.describe(CsvEncoding.plan(source, null));
        assertEquals("utf-8 (auto)", automatic);
        assertTrue(automatic.contains("(auto)"));
        assertEquals("utf-8", CsvEncoding.describe(CsvEncoding.plan(source, "utf-8")));
    }

    private static byte[] withBom(byte[] bom, byte[] text) {
        byte[] content = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(text, 0, content, bom.length, text.length);
        return content;
    }
}
