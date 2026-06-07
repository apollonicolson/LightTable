# Vendored: defport (protocol substrate — framing closure)

Source repo : git@github-typmk:typmk/defport.git (typmk-owned; no third-party license)
Source path : src/defport/
Source SHA  : 2f4ac90423310834d83f554396970a6917f10a6b  (v0.3.0)
Upstream    : ~/GitHub/defnet/defport (submodule of defnet)

## What defport is

"To protocol work what Ring is to HTTP" — a thin, runtime-agnostic (.cljc, JVM+Node)
adapter between JSON-RPC-on-the-wire and handler functions, for **LSP, DAP, MCP, CDP,
BSP, ROS2** clients and servers. 100% spec coverage (LSP 3.17, DAP 1.65, MCP 2025-11-25,
CDP 1.3 …), tested against real rust-analyzer / debugpy / headless Chromium.

This is the owned substrate for LightTable's ENTIRE protocol axis (ADR 0007): the LSP
client (`defport.lsp.client`), DAP (`defport.dap.client`), the agent channel
(`defport.mcp.client`), and devtools/E2E (`defport.cdp.client`) all ride it. It
supersedes the hand-rolled `lt.lsp.jsonrpc` codec (retracted) — `transports/framing`
does the same job, spec-verified and byte-accurate, and handles both Content-Length
(LSP/DAP) and JSON-lines (MCP) framing.

## Files vendored so far (the FRAMING closure only)

    transports/framing.cljc   Content-Length + JSON-lines codecs (encode-message/empty-state/feed)
    util/platform.cljc        platform shims (cheshire on :clj, native JSON on :cljs)

This is the base every protocol uses. The full LSP-client closure (lsp/client, lsp/spec,
lsp, transports/subprocess, registry, sugar, …) is vendored incrementally as each slice
needs it — OR the whole library is adopted via git-dep/submodule (PACKAGING DECISION
pending; see ADR 0007). cheshire/http-kit are `#?(:clj …)`-guarded, so the :cljs build
uses native JSON/WebSocket — defport runs in the Node-enabled Electron renderer.

## Re-syncing

Re-copy from the same paths at a newer SHA; update Source SHA. defport is actively
developed and typmk-owned — prefer git-dep/submodule over deep vendoring if the closure
grows large (the sig drift lesson).
