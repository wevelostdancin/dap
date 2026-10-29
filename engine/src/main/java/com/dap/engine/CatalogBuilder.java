package com.dap.engine;

import java.io.File;
import java.nio.file.Files;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/**
 * Пересобирает каталог узлов DAP в JSON-кэш.
 * Запускается offline, без HTTP: нужен для scripts/build-catalog.sh.
 */
public class CatalogBuilder implements IApplication {

    @Override
    public Object start(final IApplicationContext context) throws Exception {
        System.setProperty("java.awt.headless", "true");
        final String[] args = (String[]) context.getArguments().get("application.args");
        String out = "catalog.json";
        for (final String a : args) {
            if (a.startsWith("-out=")) {
                out = a.substring("-out=".length());
            }
        }
        try {
            org.knime.core.internal.CorePlugin.getInstance();
            final File target = new File(out);
            if (target.getParentFile() != null) {
                Files.createDirectories(target.getParentFile().toPath());
            }
            final NodeCatalog catalog = NodeCatalog.loadOrBuild(target);
            catalog.rebuild();
            catalog.save(target);
            System.out.println("[catalog] nodes=" + catalog.size()
                    + " failures=" + catalog.failures().size()
                    + " -> " + target.getAbsolutePath());
            return IApplication.EXIT_OK;
        } catch (final Throwable t) {
            System.err.println("[catalog] FAILED: " + t);
            t.printStackTrace();
            return 1;
        }
    }

    @Override
    public void stop() {
    }
}
