# Vendored: sig (cell / res substrate)

Source repo : git@github.com:hbtweb/sig.git (private)
Source path : cljc/src/
Source SHA  : 9a97649f0555644c3067cba2aad461bec978f752
License     : Apache-2.0 (see ./LICENSE)

## Why vendored

The M3/M4 substrate adoption (BOT `object/raise` → `res`, atom state → `cell`)
needs sig's reactive primitives on the LightTable shadow-cljs/Electron toolchain.
A Clojars/git-dep route is fragile here: sig's `deps.edn` declares the private
`hbt/alien-signals` JVM jar, and `deps.edn` classpath resolution fails whole on a
missing artifact — even though the **CLJS** build only needs the `.cljc` source
plus the npm `alien-signals` package. Vendoring sidesteps that and matches the
modernization's "owned substrate" direction: LightTable owns its substrate copy.

## Files (dependency closure of `cell` + `res`)

    cell.cljc            reactive cell (npm alien-signals on cljs; hbt jar on jvm)
    res.cljc             resolution (object/raise analogue)
    res/core.cljc        claim sources
    res/index.cljc       claim indexing
    res/shape.cljc       malli shape sugar

`par.cljc` and `res/adapter/*` are NOT in this closure and were left out. Add
them here (from the same SHA) if a later slice needs them.

## Runtime / build deps (already declared, not vendored)

- npm `alien-signals` — in package.json (cell's :cljs substrate)
- `metosin/malli` 0.20.1 — in shadow-cljs.edn :dependencies (res/shape, res/index)

The `#?(:clj (:import [hbt.aliensignals ...]))` branch in cell.cljc is JVM-only
and does not affect the :cljs build.

## Re-syncing

To pull a newer sig: re-copy the five files from the same paths at the new SHA,
update `Source SHA` above, and re-run `npx shadow-cljs compile test`. Note SHA
9a97649 was local-ahead-of-origin/main (4696b4d) at vendor time; push sig to make
this SHA fetchable.
