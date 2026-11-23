# dap-dsh

DSH bundle plugin for [DAP](https://github.com/wevelostdancin/dap). It registers
the DAP MCP server in the active DSH profile and prints a readable diagnostic
when the DAP binary is missing.

The bundle carries no runtime: it never downloads a binary, has no postinstall
script, and declares no capabilities.

## What it does

- Adds an `insert` row for `dap-dsh` itself to the loader patch.
- Patches `mcp-manager.config.servers.dap` to use stdio with
  `~/.dap/bin/dap mcp` and `DAP_HOME=~/.dap`.
- Sets a 60 s startup timeout, a 300 s tool-call timeout and automatic
  reconnect for the DAP server.
- Provides `bin/launch.mjs` as a standalone launcher when the MCP server is
  started outside the profile: it resolves `~/.dap/bin/dap`, checks that the
  file is executable, forwards stdio and SIGINT/SIGTERM, and prints the
  install command with exit code 2 if the binary is absent.
- Keeps `index.js` free of module-scope side effects (name, inject, apply).

## Requirements

1. DSH `>=0.2.2-0 <0.3.0` on Node.js `>=24.9.0`.
2. DAP installed **first** with `install.sh`. The plugin does not install the
   engine, JRE or adapter:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/wevelostdancin/dap/main/scripts/install.sh | sh
   ~/.dap/bin/dap doctor --client dsh
   ```

## Install

From the CLI:

```bash
dsh plugin --profile desktop add dap-dsh
```

From the Desktop UI: **Settings -> Plugins**, search for `dap-dsh`, install it
and restart DSH so the patch layer is reloaded.

## Diagnostics

- `~/.dap/bin/dap doctor --client dsh` preflights the engine, jar, MCP
  `tools/list` and client configuration.
- The launcher prints `dap-dsh: DAP <version>` (best effort) to stderr before
  starting the MCP server; an unavailable version check does not block startup.
- If `~/.dap/bin/dap` is missing or not executable, the launcher writes the
  `install.sh` command to stderr and exits with code 2.
- In an agent session, DAP tools appear as `mcp__dap__*`; call
  `mcp__dap__dap_analyze` or `mcp__dap__dap_profile` to confirm the server.

## Limitations

- The patch targets the whole `mcp-manager` row. If your profile defines
  other MCP servers in that row, merge them into your own patch layer before
  installing; see `docs/DSH-PLUGIN.md`.
- DSH may strip environment variables from child processes, so the patch uses
  an absolute path built from `process.env.HOME`. The launcher honours
  `DAP_HOME` when it is already set.

## Remove

```bash
dsh plugin --profile desktop remove dap-dsh
```

Restart DSH afterwards. Uninstalling the plugin does not delete `~/.dap`;
remove that directory yourself if you no longer need DAP.

## License

Apache-2.0. See [LICENSE](LICENSE).
