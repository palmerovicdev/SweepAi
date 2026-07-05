// Dynamic loader for the two SDKs. We import lazily so the daemon can boot even
// when node_modules/ is missing (SdkManager on the Kotlin side triggers install
// and restarts); the actual import happens on first codex.* / opencode.* call.

let codexModule = null;
let opencodeModule = null;

export async function loadCodexSdk() {
  if (codexModule) return codexModule;
  try {
    codexModule = await import('@openai/codex-sdk');
    return codexModule;
  } catch (e) {
    throw new Error(
      "Failed to load @openai/codex-sdk. Run 'Update SDKs' from Sweep settings. " +
      "Underlying error: " + (e && e.message ? e.message : String(e))
    );
  }
}

export async function loadOpencodeSdk() {
  if (opencodeModule) return opencodeModule;
  try {
    opencodeModule = await import('@opencode-ai/sdk');
    return opencodeModule;
  } catch (e) {
    throw new Error(
      "Failed to load @opencode-ai/sdk. Run 'Update SDKs' from Sweep settings. " +
      "Underlying error: " + (e && e.message ? e.message : String(e))
    );
  }
}

export async function readInstalledVersions() {
  const { readFile } = await import('node:fs/promises');
  const { fileURLToPath } = await import('node:url');
  const { dirname, resolve } = await import('node:path');

  // Bridge dir is the parent of utils/ where this file lives.
  const here = dirname(fileURLToPath(import.meta.url));
  const bridgeDir = resolve(here, '..');
  const paths = {
    codex: resolve(bridgeDir, 'node_modules/@openai/codex-sdk/package.json'),
    opencode: resolve(bridgeDir, 'node_modules/@opencode-ai/sdk/package.json'),
  };
  const result = { codex: null, opencode: null };
  for (const key of Object.keys(paths)) {
    try {
      const raw = await readFile(paths[key], 'utf8');
      const pkg = JSON.parse(raw);
      result[key] = pkg.version ?? null;
    } catch { /* not installed */ }
  }
  return result;
}
