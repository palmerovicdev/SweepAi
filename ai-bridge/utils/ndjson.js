// NDJSON writers over process.stdout. Every message is exactly one line so the
// Kotlin side can split on '\n' without reassembling fragments.

let sequence = 0;

export function nextSeq() {
  return ++sequence;
}

export function writeLine(obj) {
  const s = JSON.stringify(obj);
  if (s.includes('\n')) {
    // Defensive: JSON.stringify never emits raw newlines but a payload with
    // embedded control chars could produce one. Escape them.
    process.stdout.write(s.replace(/\n/g, '\\n') + '\n');
  } else {
    process.stdout.write(s + '\n');
  }
}

export function writeEvent(id, event, data) {
  writeLine({ id, event, data });
}

export function writeError(id, err) {
  const message = err && err.message ? err.message : String(err);
  const stack = err && err.stack ? err.stack : undefined;
  writeLine({ id, error: { message, stack } });
}

export function writeDone(id) {
  writeLine({ id, done: true });
}
