package com.dap.nativeengine.exec;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.DuckDbSession;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded job history: terminal jobs above DAP_MAX_RETAINED_JOBS are dropped oldest first,
 * active jobs are never dropped.
 */
class JobRetentionTest {
    private static final int CAP = 200;
    private static final Duration FAST = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    private DuckDbSession db;
    private WorkflowStore workflows;
    private JobManager jobs;

    @BeforeEach
    void setUp() throws Exception {
        db = new DuckDbSession();
        workflows = new WorkflowStore(10);
        jobs = new JobManager(workflows, new Executors().register(
                ExecTestSupport.fake("dap.one", (context, inputs) ->
                        ExecTestSupport.plan(db, "SELECT 1 AS x", "x"))), CAP);
    }

    @AfterEach
    void tearDown() {
        if (jobs != null) {
            jobs.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void terminalHistoryIsCappedAtTheRetentionLimit() throws Exception {
        Workflow workflow = workflows.create("s1", "wf");
        workflow.addNode("dap.one", "one", null);

        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            Job job = jobs.submit(workflow, db, 5);
            assertTrue(job.await(10_000), "job did not finish");
            ids.add(job.id());
        }

        assertEquals(CAP, jobs.totalCount(), "terminal job history must be capped");
        assertNull(jobs.find("s1", ids.get(0)), "the oldest job must be dropped");
        assertNull(jobs.find("s1", ids.get(49)), "every job above the cap must be dropped");
        assertNotNull(jobs.find("s1", ids.get(50)), "the newest CAP jobs must be kept");
        assertNotNull(jobs.find("s1", ids.get(249)));
        assertEquals(CAP, jobs.list("s1").size());
    }

    @Test
    void activeJobsAreNeverDropped() throws Exception {
        WorkflowStore small = new WorkflowStore(10);
        DuckDbSession session = new DuckDbSession();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobManager capped = new JobManager(small, new Executors()
                .register(ExecTestSupport.fake("dap.one", (context, inputs) ->
                        ExecTestSupport.plan(session, "SELECT 1 AS x", "x")))
                .register(ExecTestSupport.fake("dap.block", (context, inputs) -> {
                    started.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    return ExecTestSupport.plan(session, "SELECT 2 AS x", "x");
                })), 2);
        try {
            Workflow workflow = small.create("s1", "wf");
            workflow.addNode("dap.one", "one", null);
            Job first = capped.submit(workflow, session, 30);
            assertTrue(first.await(10_000));
            Job second = capped.submit(workflow, session, 30);
            assertTrue(second.await(10_000));

            Workflow blockedWorkflow = small.create("s1", "blocked");
            blockedWorkflow.addNode("dap.block", "block", null);
            Job active = capped.submit(blockedWorkflow, session, 30);
            assertTrue(started.await(5, TimeUnit.SECONDS), "blocking job did not start");

            Workflow moreWorkflow = small.create("s1", "more");
            moreWorkflow.addNode("dap.one", "one", null);
            Job third = capped.submit(moreWorkflow, session, 30);
            assertTrue(third.await(10_000));

            assertNotNull(capped.find("s1", active.id()), "an active job must never be dropped");
            assertNull(capped.find("s1", first.id()), "the oldest terminal job is dropped instead");

            release.countDown();
            assertTrue(active.await(10_000), "blocking job did not finish");
            assertEquals(2, capped.totalCount(), "the cap holds once the job is terminal");
        } finally {
            release.countDown();
            capped.close();
            session.close();
        }
    }

    @Test
    void httpJobListReflectsTheCap() throws Exception {
        NativeService service = new NativeService(
                new NativeService.Settings(100, 600, "127.0.0.1", 0),
                new NodeRegistry(), NativeService.defaultExecutors());
        service.start(0);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = "http://127.0.0.1:" + service.port();
        String session = "retention-" + UUID.randomUUID();
        try {
            String workflow = createWorkflow(base, client, session);
            String firstJob = null;
            for (int index = 0; index < 250; index++) {
                String jobId = submitJob(base, client, session, workflow);
                if (firstJob == null) {
                    firstJob = jobId;
                }
                awaitJob(base, client, session, jobId);
            }

            Resp listed = send(base, client, "GET", "/v1/jobs", session, null);
            assertEquals(200, listed.status(), listed.raw().body());
            assertEquals(CAP, listed.body().path("count").asInt(), listed.raw().body());
            assertEquals(CAP, listed.body().path("jobs").size());

            Resp oldest = send(base, client, "GET", "/v1/jobs/" + firstJob, session, null);
            assertEquals(404, oldest.status(), "the oldest job must be gone: " + oldest.raw().body());
        } finally {
            service.close();
        }
    }

    // --------------------------------------------------------------- helpers

    private static String createWorkflow(String base, HttpClient client, String session)
            throws Exception {
        Resp created = send(base, client, "POST", "/v1/workflows", session,
                Map.of("name", "retention"));
        assertEquals(200, created.status(), created.raw().body());
        String workflow = created.body().path("id").asText();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("factory", "dap.csv_reader");
        node.put("settings", Map.of("file", csvFile()));
        Resp added = send(base, client, "POST", "/v1/workflows/" + workflow + "/nodes",
                session, node);
        assertEquals(200, added.status(), added.raw().body());
        return workflow;
    }

    private static String csvFile() throws Exception {
        Path file = Files.createTempFile("dap-retention-", ".csv");
        Files.writeString(file, "name\nalice\n", StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();
        return file.toString();
    }

    private static String submitJob(String base, HttpClient client, String session,
                                    String workflow) throws Exception {
        Resp submitted = send(base, client, "POST", "/v1/workflows/" + workflow + "/jobs",
                session, Map.of());
        assertEquals(202, submitted.status(), submitted.raw().body());
        return submitted.body().path("id").asText();
    }

    private static void awaitJob(String base, HttpClient client, String session, String jobId)
            throws Exception {
        long deadline = System.nanoTime() + FAST.toNanos();
        while (System.nanoTime() < deadline) {
            Resp response = send(base, client, "GET", "/v1/jobs/" + jobId, session, null);
            assertEquals(200, response.status(), response.raw().body());
            if ("succeeded".equals(response.body().path("state").asText())) {
                return;
            }
            Thread.sleep(2);
        }
        throw new AssertionError("job " + jobId + " did not succeed");
    }

    private static Resp send(String base, HttpClient client, String method, String path,
                             String session, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-DAP-Session", session)
                .timeout(FAST);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = response.body() == null || response.body().isBlank()
                ? Json.object()
                : Json.MAPPER.readTree(response.body());
        return new Resp(response.statusCode(), parsed, response);
    }

    private record Resp(int status, JsonNode body, HttpResponse<String> raw) {
    }
}
