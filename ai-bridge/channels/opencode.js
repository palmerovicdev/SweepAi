// OpenCode channel — wraps @opencode-ai/sdk. On first use spawns an in-process
// opencode server via `createOpencode()`, then maps its SSE event stream into
// the normalized NDJSON event vocabulary (`text.delta`, `tool.start`,
// `tool.done`, `permission.request`, `turn.done`).

import { loadOpencodeSdk } from '../utils/sdk-loader.js';

// Lazily-created singleton — one server for the whole daemon process.
let clientPromise = null;
// Per-session event listeners. Keyed by sessionID.
const listeners = new Map();
// Diff tracker: `${sessionID}|${partId}` -> last-seen text
const seenText = new Map();
// Per-inflight request AbortController.
const inflight = new Map();

export async function handleOpencode({ id, method, params, write }) {
  switch (method) {
    case 'listModels':      return listModels({ id, params, write });
    case 'createSession':   return createSession({ id, params, write });
    case 'resumeSession':   return resumeSession({ id, params, write });
    case 'send':            return send({ id, params, write });
    case 'cancel':          return cancel({ id, params, write });
    case 'answerPermission': return answerPermission({ id, params, write });
    default:
      write({ error: { code: -32601, message: `Unknown opencode method: ${method}` } });
      write({ done: true });
  }
}

async function listModels({ params, write }) {
  try {
    const { client } = await ensureClient();
    const options = params?.cwd ? { query: { directory: params.cwd } } : undefined;
    const response = client.provider?.list
      ? await client.provider.list(options)
      : await client.config.providers(options);
    const data = response?.data ?? response ?? {};
    const providers = data.all ?? data.providers ?? [];
    const connected = Array.isArray(data.connected) ? new Set(data.connected) : null;
    const models = [];

    for (const provider of providers) {
      if (connected && !connected.has(provider.id)) continue;
      for (const [key, model] of Object.entries(provider.models ?? {})) {
        const modelID = model?.id ?? key;
        if (!provider.id || !modelID) continue;
        models.push({
          providerId: provider.id,
          providerName: provider.name ?? provider.id,
          modelId: modelID,
          name: model?.name ?? modelID,
        });
      }
    }

    models.sort((a, b) =>
      a.providerName.localeCompare(b.providerName)
      || a.name.localeCompare(b.name)
      || a.modelId.localeCompare(b.modelId));
    write({ event: 'models', data: { models } });
    write({ done: true });
  } catch (e) {
    write({ error: { message: `opencode.listModels failed: ${e && e.message ? e.message : e}` } });
    write({ done: true });
  }
}

async function ensureClient() {
  if (clientPromise) return clientPromise;
  const sdk = await loadOpencodeSdk();
  clientPromise = (async () => {
    // port: 0 → OS picks a free port. Prevents "port 4096 in use" collisions
    // with any user-launched `opencode serve` and with sibling IDE instances.
    const { client, server } = await sdk.createOpencode({
      hostname: '127.0.0.1',
      port: 0,
    });
    // Fan out server-side events to per-session listeners.
    fanOutEvents(client).catch((e) => {
      process.stderr.write(`[opencode] event stream aborted: ${e && e.message ? e.message : e}\n`);
      clientPromise = null;
    });
    return { client, server };
  })();
  return clientPromise;
}

async function fanOutEvents(client) {
  const { stream } = await client.event();
  for await (const event of stream) {
    routeGlobalEvent(event);
  }
}

function routeGlobalEvent(event) {
  const props = event?.properties;
  if (!props) return;
  // Extract sessionID either directly or from the part payload.
  const sessionID = props.sessionID
    || props.part?.sessionID
    || props.sessionId
    || null;
  if (!sessionID) return;
  const listener = listeners.get(sessionID);
  if (!listener) return;
  listener(event);
}

async function createSession({ id, params, write }) {
  try {
    const { client } = await ensureClient();
    const res = await client.session.create({
      query: { directory: params.cwd },
      body: {},
    });
    const sessionID = res?.data?.id;
    if (!sessionID) throw new Error('opencode session.create returned no id');
    write({ event: 'session.created', data: { sessionId: sessionID } });
    write({ done: true });
  } catch (e) {
    write({ error: { message: `opencode.createSession failed: ${e && e.message ? e.message : e}` } });
    write({ done: true });
  }
}

async function resumeSession({ id, params, write }) {
  try {
    const { client } = await ensureClient();
    const res = await client.session.get({ path: { id: params.sessionId }, query: { directory: params.cwd } });
    if (res?.data?.id) {
      write({ event: 'session.resumed', data: { sessionId: params.sessionId } });
      write({ done: true });
    } else {
      write({ error: { code: -32602, message: 'session not found' } });
      write({ done: true });
    }
  } catch (e) {
    const message = e && e.message ? e.message : String(e);
    const notFound = /404|not found/i.test(message);
    write({ error: { code: notFound ? -32602 : undefined, message: `opencode.resumeSession failed: ${message}` } });
    write({ done: true });
  }
}

async function send({ id, params, write }) {
  const sessionID = params.sessionId;
  const controller = new AbortController();
  inflight.set(id, { sessionID, controller });

  const listener = (event) => routeSessionEvent(event, sessionID, write);
  listeners.set(sessionID, listener);

  try {
    const { client } = await ensureClient();
    const body = {
      parts: [{ type: 'text', text: params.prompt }],
    };
    if (params.agent) body.agent = params.agent;
    if (params.model) {
      if (typeof params.model === 'string') {
        const [providerID, modelID] = params.model.split('/');
        if (providerID && modelID) body.model = { providerID, modelID };
      } else if (params.model?.providerID && params.model?.modelID) {
        body.model = params.model;
      }
    }
    // Fire the prompt and let the SSE stream feed events. `promptAsync` returns
    // as soon as the message is enqueued so we don't block the daemon.
    const promptPromise = client.session.promptAsync({
      path: { id: sessionID },
      query: { directory: params.cwd },
      body,
      signal: controller.signal,
    });
    // Wait for the session to go idle (or errored / cancelled).
    await new Promise((resolve, reject) => {
      const originalListener = listeners.get(sessionID);
      listeners.set(sessionID, (event) => {
        originalListener?.(event);
        if (event?.type === 'session.idle' || event?.type === 'session.error') resolve();
        if (event?.type === 'session.deleted') resolve();
      });
      controller.signal.addEventListener('abort', () => resolve());
      promptPromise.catch((e) => reject(e));
    });
    write({ event: 'turn.done', data: {} });
    write({ done: true });
  } catch (e) {
    if (controller.signal.aborted) {
      write({ event: 'turn.cancelled', data: {} });
      write({ done: true });
    } else {
      write({ error: { message: `opencode.send failed: ${e && e.message ? e.message : e}` } });
      write({ done: true });
    }
  } finally {
    listeners.delete(sessionID);
    inflight.delete(id);
    for (const key of [...seenText.keys()]) {
      if (key.startsWith(sessionID + '|')) seenText.delete(key);
    }
  }
}

async function cancel({ id, params, write }) {
  try {
    const { client } = await ensureClient();
    await client.session.abort({ path: { id: params.sessionId } });
    write({ done: true });
  } catch (e) {
    write({ error: { message: `opencode.cancel failed: ${e && e.message ? e.message : e}` } });
    write({ done: true });
  }
}

// Aborts an in-flight send() call by its bridge request id. Returns true when a
// matching call was found. Invoked by daemon.js on the generic `cancel` method.
export async function abortRequest(requestId) {
  const rec = inflight.get(requestId);
  if (!rec) return false;
  try { rec.controller.abort(); } catch { /* ignore */ }
  try {
    const { client } = await ensureClient();
    await client.session.abort({ path: { id: rec.sessionID } });
  } catch { /* server may already be down */ }
  return true;
}

async function answerPermission({ id, params, write }) {
  try {
    const { client } = await ensureClient();
    // The permission endpoint path varies between SDK versions; try both known shapes.
    const permCall = client.session.permissions?.reply
      ?? client.session.permission?.reply
      ?? client.session.permission;
    if (typeof permCall !== 'function') {
      throw new Error('opencode SDK missing permission reply endpoint');
    }
    await permCall.call(client.session, {
      path: { id: params.sessionId, permissionID: params.permissionId },
      body: { response: params.allow ? 'once' : 'reject' },
    });
    write({ done: true });
  } catch (e) {
    write({ error: { message: `opencode.answerPermission failed: ${e && e.message ? e.message : e}` } });
    write({ done: true });
  }
}

// ============================================================================
// Session event routing
// ============================================================================

function routeSessionEvent(event, sessionID, write) {
  switch (event.type) {
    case 'message.part.updated': {
      routePart(event.properties.part, event.properties.delta, sessionID, write);
      return;
    }
    case 'permission.updated': {
      const p = event.properties;
      write({
        event: 'permission.request',
        data: {
          permissionId: p.id ?? p.permissionID,
          toolName: p.type ?? p.tool ?? 'permission',
          args: p.metadata ?? p.title ?? {},
        },
      });
      return;
    }
    case 'session.error': {
      const err = event.properties?.error;
      const message = err?.name || err?.message || 'session error';
      write({ event: 'error', data: { message } });
      return;
    }
    // session.idle / session.deleted are handled by the resolve() in send()
    default:
      // Forward for debugging.
      write({ event: 'sdk.raw', data: { type: event.type } });
  }
}

function routePart(part, delta, sessionID, write) {
  if (!part) return;
  switch (part.type) {
    case 'text':
    case 'reasoning': {
      const key = `${sessionID}|${part.id}`;
      const prev = seenText.get(key) || '';
      const curr = part.text || '';
      const emitted = delta ?? (curr.startsWith(prev) ? curr.slice(prev.length) : curr);
      seenText.set(key, curr);
      if (emitted && emitted.length > 0) {
        write({
          event: 'text.delta',
          data: { delta: emitted, kind: part.type === 'reasoning' ? 'thinking' : 'content' },
        });
      }
      return;
    }
    case 'tool': {
      const state = part.state;
      if (state?.status === 'running') {
        write({
          event: 'tool.start',
          data: { toolCallId: part.callID ?? part.id, toolName: part.tool, args: state.input ?? {} },
        });
      } else if (state?.status === 'completed') {
        write({
          event: 'tool.done',
          data: {
            toolCallId: part.callID ?? part.id,
            ok: true,
            output: truncate(String(state.output ?? state.title ?? ''), 100_000),
          },
        });
      } else if (state?.status === 'error') {
        write({
          event: 'tool.done',
          data: { toolCallId: part.callID ?? part.id, ok: false, output: state.error ?? 'tool failed' },
        });
      }
      return;
    }
    default:
      // step-start / step-finish / file / snapshot / patch / agent / retry / compaction — ignored for now.
  }
}

function truncate(s, max) {
  if (!s) return s;
  if (s.length <= max) return s;
  return s.slice(0, max) + `\n… [truncated ${s.length - max} chars]`;
}
