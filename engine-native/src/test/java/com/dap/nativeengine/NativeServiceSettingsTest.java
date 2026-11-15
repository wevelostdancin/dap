package com.dap.nativeengine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing and validation of the DAP_* environment settings (DuckDB resources, Phase 3). */
class NativeServiceSettingsTest {

    @Test
    void defaultSettingsLeaveDuckDbResourcesUnset() {
        NativeService.Settings settings = NativeService.Settings.fromEnv(Map.of());
        assertEquals(100, settings.maxWorkflows());
        assertEquals(600, settings.defaultJobTimeoutSec());
        assertEquals("127.0.0.1", settings.bindHost());
        assertEquals(7800, settings.port());
        assertNull(settings.duckdbMemoryLimit());
        assertNull(settings.duckdbThreads());
        assertNull(settings.duckdbTempDirectory());
    }

    @Test
    void fourArgumentConstructorLeavesDuckDbResourcesUnset() {
        NativeService.Settings settings = new NativeService.Settings(5, 30, "127.0.0.1", 7801);
        assertNull(settings.duckdbMemoryLimit());
        assertNull(settings.duckdbThreads());
        assertNull(settings.duckdbTempDirectory());
    }

    @Test
    void envNamesConfigureDuckDbResources() {
        NativeService.Settings settings = NativeService.Settings.fromEnv(Map.of(
                "DAP_DUCKDB_MEMORY_LIMIT", "2GB",
                "DAP_DUCKDB_THREADS", "4",
                "DAP_DUCKDB_TEMP_DIR", "/tmp/dap-duckdb"));
        assertEquals("2GB", settings.duckdbMemoryLimit());
        assertEquals(4, settings.duckdbThreads());
        assertEquals("/tmp/dap-duckdb", settings.duckdbTempDirectory());
    }

    @Test
    void blankDuckDbEnvValuesMeanUnset() {
        NativeService.Settings settings = NativeService.Settings.fromEnv(Map.of(
                "DAP_DUCKDB_MEMORY_LIMIT", "  ",
                "DAP_DUCKDB_THREADS", "",
                "DAP_DUCKDB_TEMP_DIR", "\t"));
        assertNull(settings.duckdbMemoryLimit());
        assertNull(settings.duckdbThreads());
        assertNull(settings.duckdbTempDirectory());
    }

    @Test
    void idleTimeoutDefaultsTo900Seconds() {
        assertEquals(900, NativeService.Settings.fromEnv(Map.of()).idleTimeoutSec());
    }

    @Test
    void envConfiguresIdleTimeoutAndZeroDisablesIt() {
        assertEquals(5, NativeService.Settings.fromEnv(
                Map.of("DAP_IDLE_TIMEOUT_SEC", " 5 ")).idleTimeoutSec());
        assertEquals(0, NativeService.Settings.fromEnv(
                Map.of("DAP_IDLE_TIMEOUT_SEC", "0")).idleTimeoutSec());
        assertEquals(900, NativeService.Settings.fromEnv(
                Map.of("DAP_IDLE_TIMEOUT_SEC", " ")).idleTimeoutSec(), "blank keeps the default");
    }

    @Test
    void invalidIdleTimeoutFailsLoudly() {
        IllegalArgumentException notANumber = assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_IDLE_TIMEOUT_SEC", "soon")));
        assertTrue(notANumber.getMessage().contains("DAP_IDLE_TIMEOUT_SEC"), notANumber.getMessage());
        assertTrue(notANumber.getMessage().contains("soon"), notANumber.getMessage());

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_IDLE_TIMEOUT_SEC", "-1")));
        assertTrue(negative.getMessage().contains("DAP_IDLE_TIMEOUT_SEC"), negative.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> new NativeService.Settings(1, 1, "127.0.0.1", 0, null, null, null, -1));
    }

    @Test
    void queryTimeoutDefaultsTo60SecondsAndIsConfigurable() {
        assertEquals(60, NativeService.Settings.fromEnv(Map.of()).queryTimeoutSec());
        assertEquals(5, NativeService.Settings.fromEnv(
                Map.of("DAP_QUERY_TIMEOUT_SEC", " 5 ")).queryTimeoutSec());
        assertEquals(0, NativeService.Settings.fromEnv(
                Map.of("DAP_QUERY_TIMEOUT_SEC", "0")).queryTimeoutSec());
        assertEquals(60, NativeService.Settings.fromEnv(
                Map.of("DAP_QUERY_TIMEOUT_SEC", " ")).queryTimeoutSec(), "blank keeps the default");

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_QUERY_TIMEOUT_SEC", "-1")));
        assertTrue(negative.getMessage().contains("DAP_QUERY_TIMEOUT_SEC"), negative.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_QUERY_TIMEOUT_SEC", "soon")));
    }

    @Test
    void invalidThreadsEnvFailsLoudly() {
        IllegalArgumentException notANumber = assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_DUCKDB_THREADS", "many")));
        assertTrue(notANumber.getMessage().contains("DAP_DUCKDB_THREADS"), notANumber.getMessage());
        assertTrue(notANumber.getMessage().contains("many"), notANumber.getMessage());

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_DUCKDB_THREADS", "0")));
        assertTrue(zero.getMessage().contains("DAP_DUCKDB_THREADS"), zero.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_DUCKDB_THREADS", "-2")));
    }
}
