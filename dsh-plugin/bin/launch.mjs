#!/usr/bin/env node
// dap-dsh fallback launcher: hand stdio to the DAP binary installed by install.sh.
// The bundle itself never downloads a binary and has no postinstall script.
import { spawn, spawnSync } from 'node:child_process';
import { accessSync, constants, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const home = process.env.HOME || homedir();
const dapHome = process.env.DAP_HOME || join(home, '.dap');
const dap = join(dapHome, 'bin', 'dap');

function fail(message) {
  process.stderr.write(`dap-dsh: ${message}\n`);
  process.exit(2);
}

if (!existsSync(dap)) {
  fail(
    `DAP binary not found at ${dap}. Install DAP with install.sh first:\n` +
    '  curl -fsSL https://raw.githubusercontent.com/wevelostdancin/dap/main/scripts/install.sh | sh'
  );
}
try {
  accessSync(dap, constants.X_OK);
} catch {
  fail(`DAP binary is not executable: ${dap}. Re-run install.sh to repair the installation.`);
}

// Diagnostics only: a missing or slow 'dap version' must not block startup.
const version = spawnSync(dap, ['version'], {
  encoding: 'utf8',
  timeout: 3000,
  stdio: ['ignore', 'pipe', 'pipe'],
});
if (version.status === 0 && version.stdout?.trim()) {
  process.stderr.write(`dap-dsh: DAP ${version.stdout.trim().split(/\r?\n/)[0]}\n`);
} else {
  process.stderr.write(`dap-dsh: DAP version check unavailable; starting ${dap}\n`);
}

const child = spawn(dap, ['mcp'], {
  stdio: 'inherit',
  env: { ...process.env, DAP_HOME: dapHome },
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => child.kill(signal));
}
child.on('error', (error) => {
  process.stderr.write(`dap-dsh: cannot start DAP MCP server: ${error.message}\n`);
  process.exitCode = 1;
});
child.on('exit', (code, signal) => {
  process.exitCode = signal ? 1 : (code ?? 0);
});
