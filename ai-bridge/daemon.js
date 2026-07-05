// Sweep AI bridge daemon: reads NDJSON requests on stdin, routes to per-provider
// channels, streams NDJSON events back on stdout. Every stdout line is exactly
// one JSON object; nothing else is written to stdout (diagnostics go to stderr).

import readline from 'node:readline';
import { handleCodex, abortRequest as abortCodex } from './channels/codex.js';
import { handleOpencode, abortRequest as abortOpencode } from './channels/opencode.js';
import { writeLine, writeError, writeDone } from './utils/ndjson.js';
import { readInstalledVersions } from './utils/sdk-loader.js';

const rl = readline.createInterface({ input: process.stdin });

process.stderr.write(`[sweep-ai-bridge] daemon booted (pid=${process.pid}, node=${process.version})\n`);

rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let req;
  try {
    req = JSON.parse(trimmed);
  } catch (e) {
    process.stderr.write(`[sweep-ai-bridge] dropping malformed line: ${trimmed.slice(0, 200)}\n`);
    return;
  }
  handleRequest(req).catch((e) => {
    writeError(req.id ?? null, e);
    writeDone(req.id ?? null);
  });
});

async function handleRequest(req) {
  const { id, method, params = {} } = req;
  if (typeof id !== 'number') {
    process.stderr.write(`[sweep-ai-bridge] request missing numeric id: ${JSON.stringify(req).slice(0, 200)}\n`);
    return;
  }

  const write = (obj) => writeLine({ id, ...obj });

  try {
    if (method === 'ping') {
      const versions = await readInstalledVersions();
      writeLine({ id, event: 'pong', data: { pid: process.pid, node: process.version, sdks: versions } });
      writeLine({ id, done: true });
      return;
    }
    if (method === 'shutdown') {
      writeLine({ id, done: true });
      process.stderr.write('[sweep-ai-bridge] shutdown requested\n');
      setTimeout(() => process.exit(0), 20);
      return;
    }
    if (method === 'cancel') {
      // Generic cancel by original bridge request id — fires the matching
      // channel's AbortController so Kotlin can stop an in-flight turn
      // when the collector is cancelled downstream.
      const requestId = params.requestId;
      const found = abortCodex(requestId) || (await abortOpencode(requestId));
      writeLine({ id, done: true });
      if (!found) {
        process.stderr.write(`[sweep-ai-bridge] cancel: no in-flight request with id=${requestId}\n`);
      }
      return;
    }
    if (method.startsWith('codex.')) {
      await handleCodex({ id, method: method.slice('codex.'.length), params, write });
      return;
    }
    if (method.startsWith('opencode.')) {
      await handleOpencode({ id, method: method.slice('opencode.'.length), params, write });
      return;
    }
    writeLine({ id, error: { code: -32601, message: `Unknown method: ${method}` } });
    writeLine({ id, done: true });
  } catch (e) {
    writeError(id, e);
    writeDone(id);
  }
}

rl.on('close', () => {
  process.stderr.write('[sweep-ai-bridge] stdin closed, exiting\n');
  process.exit(0);
});

process.on('uncaughtException', (e) => {
  process.stderr.write(`[sweep-ai-bridge] uncaughtException: ${e && e.stack ? e.stack : e}\n`);
});

process.on('unhandledRejection', (e) => {
  process.stderr.write(`[sweep-ai-bridge] unhandledRejection: ${e && e.stack ? e.stack : e}\n`);
});
