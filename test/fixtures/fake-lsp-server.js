// Minimal fake LSP server for tests — speaks Content-Length framed JSON-RPC over
// stdio so the full client stack (lt.lsp.node-transport → defport client →
// lt.lsp.service) can be exercised end-to-end WITHOUT installing clojure-lsp.
//
// Behavior: answers `initialize` with a capabilities stub; on `textDocument/
// didOpen` emits one canned `textDocument/publishDiagnostics`; exits on `exit`.
// No language analysis — the diagnostic is fixed so tests are deterministic.

let buf = Buffer.alloc(0);

function send(msg) {
  const body = Buffer.from(JSON.stringify(msg), 'utf8');
  process.stdout.write(`Content-Length: ${body.length}\r\n\r\n`);
  process.stdout.write(body);
}

function handle(msg) {
  if (msg.method === 'initialize') {
    send({ jsonrpc: '2.0', id: msg.id,
           result: { capabilities: { textDocumentSync: 1 },
                     serverInfo: { name: 'fake-lsp', version: '0.0.1' } } });
  } else if (msg.method === 'textDocument/didOpen') {
    const uri = msg.params.textDocument.uri;
    send({ jsonrpc: '2.0', method: 'textDocument/publishDiagnostics',
           params: { uri, diagnostics: [
             { range: { start: { line: 0, character: 0 }, end: { line: 0, character: 3 } },
               severity: 1, message: 'fake diagnostic' } ] } });
  } else if (msg.method === 'shutdown') {
    send({ jsonrpc: '2.0', id: msg.id, result: null });
  } else if (msg.method === 'exit') {
    process.exit(0);
  }
}

process.stdin.on('data', (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  for (;;) {
    const sep = buf.indexOf('\r\n\r\n');
    if (sep === -1) break;
    const header = buf.slice(0, sep).toString('ascii');
    const m = /Content-Length:\s*(\d+)/i.exec(header);
    const start = sep + 4;
    if (!m) { buf = buf.slice(start); continue; }
    const len = parseInt(m[1], 10);
    if (buf.length < start + len) break;
    const body = buf.slice(start, start + len).toString('utf8');
    buf = buf.slice(start + len);
    let msg;
    try { msg = JSON.parse(body); } catch (e) { continue; }
    handle(msg);
  }
});
