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

## Packaging decision (resolved 2026-06-07): FULL-SRC VENDOR

The entire `defport/src` tree is vendored here (42 namespaces) at the SHA above —
one shot, not file-by-file. Rationale: git-dep would force a shadow→deps.edn build-arch
migration (real risk, a detour); nested submodules (defport inside the lighttable
submodule) are two-level-painful; incremental vendoring means chasing transitive deps
each slice. For an OWNED lib (typmk controls both ends), full-src vendor at a recorded
SHA + re-sync is the simplest thing that works — mergeable, portable, all protocols
present. shadow compiles only the required closure, so unused namespaces sit harmless.
Graduate to a git-dep if drift becomes painful (re-sync = re-copy `src/` at a new SHA).

cheshire/http-kit are `#?(:clj …)`-guarded, so the :cljs build uses native JSON/
WebSocket — defport runs in the Node-enabled Electron renderer. cheshire is on the
COMPILE classpath (shadow-cljs.edn) only for `util.platform`'s :include-macros loading.

Verified in our toolchain (test/lt/lsp/): `defport_smoke_test` (framing: Content-Length
round-trip + streaming) and `client_test` (LSP client core: request/response correlation
by id + notification dispatch, via a fake transport). The live subprocess-transport
integration against a real server (clojure-lsp — not installed here) is the next slice.

## Re-syncing

Re-copy from the same paths at a newer SHA; update Source SHA. defport is actively
developed and typmk-owned — prefer git-dep/submodule over deep vendoring if the closure
grows large (the sig drift lesson).
