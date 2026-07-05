// Codex channel — wraps @openai/codex-sdk. Maps the SDK's `runStreamed`
// AsyncGenerator into the normalized NDJSON event vocabulary the Kotlin side
// expects (`text.delta`, `tool.start`, `tool.done`, `permission.request`,
// `turn.done`).

import { loadCodexSdk } from '../utils/sdk-loader.js';

// threadId -> { thread, options }
const threads = new Map();
// requestId -> AbortController (used by codex.cancel to interrupt a live turn)
const inflight = new Map();
// itemId -> last-seen text (per-thread text/reasoning diff tracker)
const seenText = new Map();

export async function handleCodex({ id, method, params, write }) {
  switch (method) {
    case 'startThread':  return startThread({ id, params, write });
    case 'resumeThread': return resumeThread({ id, params, write });
    case 'send':         return send({ id, params, write });
    case 'cancel':       return cancel({ id, params, write });
    default:
      write({ error: { code: -32601, message: `Unknown codex method: ${method}` } });
      write({ done: true });
  }
}

async function startThread({ id, params, write }) {
  const sdk = await loadCodexSdk();
  const { Codex } = sdk;
  const codexOpts = buildCodexOptions(params);
  const client = new Codex(codexOpts);
  const threadOpts = buildThreadOptions(params);
  const thread = client.startThread(threadOpts);
  // Thread id is only populated after the first turn — hand out a temporary
  // synthetic id and mint the real one when it arrives. Kotlin keys sessions
  // by whatever we return here, so we resolve the alias at first turn.
  const tempId = 'thr_pending_' + Math.random().toString(36).slice(2);
  threads.set(tempId, { thread, client, threadOpts, realId: null });
  write({ event: 'thread.created', data: { threadId: tempId } });
  write({ done: true });
}

async function resumeThread({ id, params, write }) {
  const sdk = await loadCodexSdk();
  const { Codex } = sdk;
  const codexOpts = buildCodexOptions(params);
  const client = new Codex(codexOpts);
  const threadOpts = buildThreadOptions(params);
  try {
    const thread = client.resumeThread(params.threadId, threadOpts);
    threads.set(params.threadId, { thread, client, threadOpts, realId: params.threadId });
    write({ event: 'thread.resumed', data: { threadId: params.threadId } });
    write({ done: true });
  } catch (e) {
    write({ error: { message: `codex.resumeThread failed: ${e && e.message ? e.message : e}` } });
    write({ done: true });
  }
}

async function send({ id, params, write }) {
  const key = params.threadId;
  const ctx = threads.get(key);
  if (!ctx) {
    write({ error: { message: `Unknown Codex threadId: ${key}` } });
    write({ done: true });
    return;
  }
  // Live settings sync: re-hydrate thread options from the request so pill
  // changes on the Kotlin side take effect on the next send without a restart.
  ctx.threadOpts = mergeThreadOptions(ctx.threadOpts, buildThreadOptions(params));

  const controller = new AbortController();
  inflight.set(id, controller);
  const input = buildInput(params.prompt, params.images);
  try {
    const { events } = await ctx.thread.runStreamed(input, { signal: controller.signal });
    for await (const event of events) {
      routeThreadEvent(event, key, ctx, write);
      if (event.type === 'turn.completed' || event.type === 'turn.failed' || event.type === 'error') {
        break;
      }
    }
    write({ done: true });
  } catch (e) {
    if (controller.signal.aborted) {
      write({ event: 'turn.cancelled', data: {} });
      write({ done: true });
    } else {
      write({ error: { message: `codex.send failed: ${e && e.message ? e.message : e}` } });
      write({ done: true });
    }
  } finally {
    inflight.delete(id);
  }
}

async function cancel({ id, params, write }) {
  const requestId = params.requestId;
  if (typeof requestId === 'number' && inflight.has(requestId)) {
    try { inflight.get(requestId).abort(); } catch { /* ignore */ }
  }
  write({ done: true });
}

// Aborts an in-flight send() call by its bridge request id. Returns true when a
// matching call was found. Invoked by daemon.js on the generic `cancel` method.
export function abortRequest(requestId) {
  const controller = inflight.get(requestId);
  if (!controller) return false;
  try { controller.abort(); } catch { /* ignore */ }
  return true;
}

// ============================================================================
// Event routing
// ============================================================================

function routeThreadEvent(event, threadKey, ctx, write) {
  switch (event.type) {
    case 'thread.started': {
      // Real thread id arrives here. Remap the alias so subsequent sends work.
      const wasPending = !ctx.realId;
      if (event.thread_id && wasPending) {
        ctx.realId = event.thread_id;
        threads.set(event.thread_id, ctx);
      }
      write({ event: 'thread.started', data: { threadId: event.thread_id } });
      // When we bind a real id for the first time, tell Kotlin so it can
      // update the persisted session row. Without this the session store
      // holds the synthetic tempId and resume across IDE restarts fails.
      if (wasPending && event.thread_id && threadKey !== event.thread_id) {
        write({
          event: 'session.id_updated',
          data: { oldId: threadKey, newId: event.thread_id },
        });
      }
      return;
    }
    case 'turn.started': {
      write({ event: 'turn.started', data: {} });
      return;
    }
    case 'item.started':
    case 'item.updated':
    case 'item.completed': {
      routeItem(event, threadKey, write);
      return;
    }
    case 'turn.completed': {
      // Clean up per-turn diff trackers so the next turn starts fresh.
      for (const key of [...seenText.keys()]) {
        if (key.startsWith(threadKey + ':')) seenText.delete(key);
      }
      write({ event: 'turn.done', data: { usage: event.usage || {} } });
      return;
    }
    case 'turn.failed': {
      write({ event: 'turn.failed', data: { message: event.error?.message || 'turn failed' } });
      return;
    }
    case 'error': {
      write({ event: 'error', data: { message: event.message } });
      return;
    }
    default:
      // Unknown SDK event — forward as-is for forward compatibility.
      write({ event: 'sdk.raw', data: event });
  }
}

function routeItem(event, threadKey, write) {
  const item = event.item;
  const t = item.type;
  switch (t) {
    case 'agent_message':
    case 'reasoning': {
      const key = `${threadKey}:${item.id}`;
      const prev = seenText.get(key) || '';
      const curr = item.text || '';
      const delta = curr.startsWith(prev) ? curr.slice(prev.length) : curr;
      seenText.set(key, curr);
      if (delta.length > 0) {
        write({ event: 'text.delta', data: { delta, kind: t === 'reasoning' ? 'thinking' : 'content' } });
      }
      return;
    }
    case 'command_execution': {
      if (event.type === 'item.started') {
        write({
          event: 'tool.start',
          data: {
            toolCallId: item.id,
            toolName: 'bash',
            args: { command: item.command },
          },
        });
      } else if (event.type === 'item.completed') {
        write({
          event: 'tool.done',
          data: {
            toolCallId: item.id,
            ok: item.status === 'completed' && (item.exit_code == null || item.exit_code === 0),
            output: truncate(item.aggregated_output || '', 100_000),
          },
        });
      }
      return;
    }
    case 'file_change': {
      if (event.type === 'item.started') {
        write({
          event: 'tool.start',
          data: {
            toolCallId: item.id,
            toolName: 'apply_patch',
            args: { changes: item.changes },
          },
        });
      } else if (event.type === 'item.completed') {
        write({
          event: 'tool.done',
          data: {
            toolCallId: item.id,
            ok: item.status === 'completed',
            output: item.status === 'completed'
              ? `Applied ${item.changes.length} file change(s)`
              : `Failed to apply ${item.changes.length} file change(s)`,
          },
        });
      }
      return;
    }
    case 'mcp_tool_call': {
      if (event.type === 'item.started') {
        write({
          event: 'tool.start',
          data: {
            toolCallId: item.id,
            toolName: `${item.server}.${item.tool}`,
            args: item.arguments,
          },
        });
      } else if (event.type === 'item.completed') {
        const outText = item.error
          ? item.error.message
          : summarizeMcpContent(item.result);
        write({
          event: 'tool.done',
          data: {
            toolCallId: item.id,
            ok: item.status === 'completed',
            output: truncate(outText || '', 100_000),
          },
        });
      }
      return;
    }
    case 'web_search': {
      if (event.type === 'item.started') {
        write({
          event: 'tool.start',
          data: { toolCallId: item.id, toolName: 'web_search', args: { query: item.query } },
        });
      } else if (event.type === 'item.completed') {
        write({
          event: 'tool.done',
          data: { toolCallId: item.id, ok: true, output: `Search completed for "${item.query}"` },
        });
      }
      return;
    }
    case 'todo_list': {
      // Not modeled as a tool call — surface as a compact status message.
      if (event.type === 'item.completed' || event.type === 'item.updated') {
        const rendered = (item.items || [])
          .map(t => `${t.completed ? '[x]' : '[ ]'} ${t.text}`)
          .join('\n');
        write({ event: 'text.delta', data: { delta: `\n${rendered}\n`, kind: 'content' } });
      }
      return;
    }
    case 'error': {
      write({ event: 'error', data: { message: item.message } });
      return;
    }
    default:
      // Unknown item type — forward for debugging.
      write({ event: 'sdk.raw', data: event });
  }
}

// ============================================================================
// Helpers
// ============================================================================

function buildCodexOptions(params) {
  const opts = {};
  if (params.codexPathOverride) opts.codexPathOverride = params.codexPathOverride;
  if (params.baseUrl) opts.baseUrl = params.baseUrl;
  if (params.apiKey) opts.apiKey = params.apiKey;
  if (params.config && typeof params.config === 'object') opts.config = params.config;
  return opts;
}

function buildThreadOptions(params) {
  const opts = {};
  if (params.model) opts.model = params.model;
  if (params.approvalPolicy) opts.approvalPolicy = params.approvalPolicy;
  if (params.sandbox) opts.sandboxMode = params.sandbox;
  if (params.cwd) opts.workingDirectory = params.cwd;
  if (params.reasoningEffort) opts.modelReasoningEffort = params.reasoningEffort;
  if (params.skipGitRepoCheck != null) opts.skipGitRepoCheck = !!params.skipGitRepoCheck;
  return opts;
}

function mergeThreadOptions(prev, curr) {
  return { ...(prev || {}), ...(curr || {}) };
}

function buildInput(prompt, images) {
  if (!images || images.length === 0) return prompt;
  const parts = [{ type: 'text', text: prompt }];
  for (const img of images) {
    if (img && img.path) parts.push({ type: 'local_image', path: img.path });
  }
  return parts;
}

function summarizeMcpContent(result) {
  if (!result) return '';
  if (Array.isArray(result.content)) {
    return result.content
      .map(c => (c && c.type === 'text' ? c.text : `[${c && c.type ? c.type : 'unknown'}]`))
      .join('\n');
  }
  return JSON.stringify(result);
}

function truncate(s, max) {
  if (!s) return s;
  if (s.length <= max) return s;
  return s.slice(0, max) + `\n… [truncated ${s.length - max} chars]`;
}
