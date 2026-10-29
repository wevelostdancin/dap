package com.dap.engine;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.RowIterator;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.workflow.NodeContainer;
import org.knime.core.node.workflow.NodeOutPort;
import org.knime.core.node.workflow.WorkflowLoadHelper;
import org.knime.core.node.workflow.WorkflowManager;
import org.knime.core.node.workflow.WorkflowPersistor;
import org.osgi.framework.Bundle;

/**
 * Minimal headless runner: loads a workflow, executes it, dumps every
 * BufferedDataTable output as CSV. Proof of concept for the MCP bridge.
 */
public class BatchRunner implements IApplication {

    @Override
    public Object start(final IApplicationContext context) throws Exception {
        System.setProperty("java.awt.headless", "true");

        final String[] args = (String[]) context.getArguments().get("application.args");
        final Map<String, String> opts = parse(args);
        final String workflowDir = opts.get("workflowDir");
        final String outDir = opts.getOrDefault("outDir", "knime-out");

        if (workflowDir == null) {
            System.err.println("usage: -application com.dap.engine.headless -workflowDir=<dir> [-outDir=<dir>]");
            return 2;
        }

        try {
            // Core + node factory registry, exactly like the product's batch app does.
            org.knime.core.internal.CorePlugin.getInstance();
            final Bundle wbCore = Platform.getBundle("org.knime.workbench.core");
            if (wbCore != null) {
                try {
                    wbCore.start(Bundle.START_TRANSIENT);
                } catch (final Exception e) {
                    System.out.println("[knime-headless] workbench.core not started: " + e.getMessage());
                }
            }

            final ExecutionMonitor exec = new ExecutionMonitor();
            final WorkflowPersistor.WorkflowLoadResult result =
                    WorkflowManager.loadProject(new File(workflowDir), exec, new WorkflowLoadHelper());
            final WorkflowManager wfm = result.getWorkflowManager();
            System.out.println("[knime-headless] loaded: " + wfm.getName()
                    + " nodes=" + wfm.getNodeContainers().size());

            final long t0 = System.currentTimeMillis();
            wfm.executeAllAndWaitUntilDone();
            System.out.println("[knime-headless] executed in "
                    + (System.currentTimeMillis() - t0) + " ms, state=" + wfm.getState());

            final File out = new File(outDir);
            Files.createDirectories(out.toPath());
            dump(wfm, out);
            wfm.shutdown();
            return IApplication.EXIT_OK;
        } catch (final Throwable t) {
            System.err.println("[knime-headless] FAILED: " + t);
            t.printStackTrace();
            return 1;
        }
    }

    private static void dump(final WorkflowManager wfm, final File out) throws Exception {
        int written = 0;
        for (final NodeContainer nc : wfm.getNodeContainers()) {
            for (int i = 0; i < nc.getNrOutPorts(); i++) {
                final NodeOutPort port = nc.getOutPort(i);
                final PortObject po;
                try {
                    po = port.getPortObject();
                } catch (final Exception e) {
                    continue;
                }
                if (!(po instanceof BufferedDataTable)) {
                    continue;
                }
                final BufferedDataTable table = (BufferedDataTable) po;
                final String name = ("out_" + nc.getName() + "_" + i + ".csv")
                        .replaceAll("[^A-Za-z0-9._-]+", "_");
                final File target = new File(out, name);
                writeCsv(table, target);
                System.out.println("[knime-headless] wrote " + target
                        + " rows=" + table.size() + " cols=" + table.getDataTableSpec().getNumColumns());
                written++;
            }
        }
        System.out.println("[knime-headless] tables written: " + written);
    }

    private static void writeCsv(final BufferedDataTable table, final File target) throws Exception {
        final DataTableSpec spec = table.getDataTableSpec();
        try (BufferedWriter w = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8)) {
            final String[] names = spec.getColumnNames();
            w.write(String.join(",", names));
            w.newLine();
            final RowIterator it = table.iterator();
            while (it.hasNext()) {
                final DataRow row = it.next();
                final StringBuilder sb = new StringBuilder();
                for (int c = 0; c < names.length; c++) {
                    if (c > 0) {
                        sb.append(',');
                    }
                    sb.append(quote(row.getCell(c).toString()));
                }
                w.write(sb.toString());
                w.newLine();
            }
        }
    }

    private static String quote(final String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static Map<String, String> parse(final String[] args) {
        final Map<String, String> map = new HashMap<>();
        if (args == null) {
            return map;
        }
        for (final String a : args) {
            if (!a.startsWith("-")) {
                continue;
            }
            final int eq = a.indexOf('=');
            if (eq > 0) {
                map.put(a.substring(1, eq), a.substring(eq + 1));
            }
        }
        return map;
    }

    @Override
    public void stop() {
    }
}
