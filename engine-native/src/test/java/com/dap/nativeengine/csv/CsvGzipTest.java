package com.dap.nativeengine.csv;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvGzipTest {
    private static final String CSV = "id,name,score\n1,alpha,1.0\n2,beta,2.5\n3,gamma,\n";

    @TempDir
    Path tempDir;

    private static Path plain(Path directory) throws IOException {
        Path file = directory.resolve("data.csv");
        Files.writeString(file, CSV, StandardCharsets.UTF_8);
        return file;
    }

    private static Path gzip(Path directory) throws IOException {
        Path file = directory.resolve("data.csv.gz");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(buffer)) {
            out.write(CSV.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(file, buffer.toByteArray());
        return file;
    }

    private static Path zstd(Path directory) throws IOException {
        Path file = directory.resolve("data.csv.zst");
        Files.write(file, new byte[]{0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x00, 0x01});
        return file;
    }

    private static List<List<String>> rows(ArrowTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (int row = 0; row < table.rowCount(); row++) {
            List<String> values = new ArrayList<>();
            for (int column = 0; column < table.columns().size(); column++) {
                Object value = table.get(column, row);
                values.add(value == null ? null : value.toString());
            }
            rows.add(values);
        }
        return rows;
    }

    private static List<ColumnSpec> columns(ArrowTable table) {
        return table.columns();
    }

    private static CsvTableReader.Options options(CsvTableReader.Compression compression) {
        return new CsvTableReader.Options(',', true, List.of(), false, compression);
    }

    @Test
    void autoDetectsGzipAndMatchesThePlainTwin() throws Exception {
        Path plainFile = plain(tempDir);
        Path gzipFile = gzip(tempDir);
        try (ArrowTable plainTable = CsvTableReader.read(plainFile, options(CsvTableReader.Compression.AUTO));
             ArrowTable gzipTable = CsvTableReader.read(gzipFile, options(CsvTableReader.Compression.AUTO))) {
            assertEquals(columns(plainTable), columns(gzipTable));
            assertEquals(rows(plainTable), rows(gzipTable));
            assertEquals(3, gzipTable.rowCount());
            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"),
                    ColumnSpec.of("score", "Float64")), columns(gzipTable));
        }
    }

    @Test
    void explicitGzipReadsTheFile() throws Exception {
        try (ArrowTable table = CsvTableReader.read(gzip(tempDir),
                options(CsvTableReader.Compression.GZIP))) {
            assertEquals(3, table.rowCount());
            assertEquals("beta", table.get(1, 1));
        }
    }

    @Test
    void noneOnAGzipFileIsRejectedWithAClearMessage() throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CsvTableReader.read(gzip(tempDir), options(CsvTableReader.Compression.NONE)));
        assertTrue(failure.getMessage().contains("gzip"), failure.getMessage());
        assertTrue(failure.getMessage().contains("compression"), failure.getMessage());
    }

    @Test
    void gzipOnAPlainFileIsRejectedWithAClearMessage() throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CsvTableReader.read(plain(tempDir), options(CsvTableReader.Compression.GZIP)));
        assertTrue(failure.getMessage().contains("gzip"), failure.getMessage());
    }

    @Test
    void zstdIsRejectedAsOutOfScope() throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CsvTableReader.read(zstd(tempDir), options(CsvTableReader.Compression.AUTO)));
        assertTrue(failure.getMessage().contains("zstd"), failure.getMessage());
    }

    @Test
    void plainFileIsUnchangedUnderAuto() throws Exception {
        try (ArrowTable table = CsvTableReader.read(plain(tempDir), options(CsvTableReader.Compression.AUTO))) {
            assertEquals(3, table.rowCount());
            assertEquals(List.of("1", "alpha", "1.0"), rows(table).get(0));
        }
    }
}
