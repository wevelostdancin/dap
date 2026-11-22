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
    void defaultSettingsUseOutOfCoreDuckDbResources() {
        NativeService.Settings settings = NativeService.Settings.fromEnv(Map.of());
        assertEquals(100, settings.maxWorkflows());
        assertEquals(600, settings.defaultJobTimeoutSec());
        assertEquals("127.0.0.1", settings.bindHost());
        assertEquals(7800, settings.port());
        assertEquals("512MB", settings.duckdbMemoryLimit(), "out-of-core default memory limit");
        assertNull(settings.duckdbThreads());
        assertEquals(System.getProperty("user.home") + "/.dap/tmp", settings.duckdbTempDirectory(),
                "out-of-core default spill directory");
        assertEquals("/tmp/dap/build/duckdb-tmp", NativeService.Settings.fromEnv(
                Map.of("DAP_HOME", "/tmp/dap")).duckdbTempDirectory());
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
    void blankDuckDbEnvValuesKeepTheDefaults() {
        NativeService.Settings settings = NativeService.Settings.fromEnv(Map.of(
                "DAP_DUCKDB_MEMORY_LIMIT", "  ",
                "DAP_DUCKDB_THREADS", "",
                "DAP_DUCKDB_TEMP_DIR", "\t"));
        assertEquals("512MB", settings.duckdbMemoryLimit());
        assertNull(settings.duckdbThreads());
        assertEquals(System.getProperty("user.home") + "/.dap/tmp", settings.duckdbTempDirectory());
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
    void sessionLimitsDefaultAndAreConfigurable() {
        assertEquals(64, NativeService.Settings.fromEnv(Map.of()).maxSessions());
        assertEquals(1800, NativeService.Settings.fromEnv(Map.of()).sessionTtlSec());
        assertEquals(4, NativeService.Settings.fromEnv(
                Map.of("DAP_MAX_SESSIONS", "4")).maxSessions());
        assertEquals(0, NativeService.Settings.fromEnv(
                Map.of("DAP_SESSION_TTL_SEC", "0")).sessionTtlSec(), "0 disables the TTL");
        assertEquals(1800, NativeService.Settings.fromEnv(
                Map.of("DAP_SESSION_TTL_SEC", " ")).sessionTtlSec(), "blank keeps the default");

        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_MAX_SESSIONS", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_SESSION_TTL_SEC", "-1")));
    }

    @Test
    void retainedJobsDefaultTo200AndAreConfigurable() {
        assertEquals(200, NativeService.Settings.fromEnv(Map.of()).maxRetainedJobs());
        assertEquals(5, NativeService.Settings.fromEnv(
                Map.of("DAP_MAX_RETAINED_JOBS", " 5 ")).maxRetainedJobs());

        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_MAX_RETAINED_JOBS", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_MAX_RETAINED_JOBS", "-3")));
        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_MAX_RETAINED_JOBS", "many")));
    }

    @Test
    void tableLimitDefaultsTo10000AndIsConfigurable() {
        assertEquals(10_000, NativeService.Settings.fromEnv(Map.of()).maxTableLimit());
        assertEquals(17, NativeService.Settings.fromEnv(
                Map.of("DAP_TABLE_MAX_LIMIT", " 17 ")).maxTableLimit());

        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_TABLE_MAX_LIMIT", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> NativeService.Settings.fromEnv(Map.of("DAP_TABLE_MAX_LIMIT", "many")));
    }

    @Test
    void extensionDirectoryDefaultsToDapHomeThenUserHome() {
        assertEquals("/tmp/dap/build/duckdb-ext", NativeService.Settings.fromEnv(
                Map.of("DAP_HOME", "/tmp/dap")).duckdbExtensionDirectory());
        assertEquals(System.getProperty("user.home") + "/.dap/extensions",
                NativeService.Settings.fromEnv(Map.of()).duckdbExtensionDirectory());
        assertEquals("/tmp/ext", NativeService.Settings.fromEnv(
                Map.of("DAP_DUCKDB_EXTENSION_DIR", " /tmp/ext ")).duckdbExtensionDirectory());
        assertEquals("/tmp/dap/build/duckdb-ext", NativeService.Settings.fromEnv(
                Map.of("DAP_HOME", "/tmp/dap", "DAP_DUCKDB_EXTENSION_DIR", "  "))
                .duckdbExtensionDirectory(), "a blank override keeps the default");
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
