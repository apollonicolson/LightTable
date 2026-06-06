(ns lt.util.broker
  "Single boundary for host (Node / Electron-main) capabilities — migration M1
  (ADR 0004, branch-by-abstraction toward sandboxing).

  Renderer code calls broker accessors instead of `(js/require ...)` directly, so
  the implementation can later move behind a preload `contextBridge` + `ipcMain`
  and the renderer can run with `contextIsolation:true` / `nodeIntegration:false`
  / `sandbox:true` WITHOUT touching any caller.

  Step 1 (current): thin pass-through to the same in-renderer requires — zero
  behaviour change, just the seam. Step 2: route the impl through a preload
  bridge. Step 3: flip the webPreferences and add a CSP. Only this namespace
  changes between steps.

  Capability surface (current host-call inventory):
    node core      — path, fs, os, net, zlib, child_process
    electron       — renderer-safe modules (ipcRenderer, clipboard, shell, webFrame)
    electron-main  — via the electron/remote proxy (app, screen, dialog, Menu, BrowserWindow)")

;; --- node core -------------------------------------------------------------
(def path "Node path module."           (js/require "path"))
(def fs   "Node fs module."             (js/require "fs"))
(def os   "Node os module."             (js/require "os"))
(def net  "Node net module."           (js/require "net"))
(def zlib "Node zlib module."           (js/require "zlib"))
(def util "Node util module."           (js/require "util"))
(def child-process "Node child_process." (js/require "child_process"))

;; --- electron (renderer-safe) ----------------------------------------------
;; electron + remote are Electron-only and are required defensively: in a plain
;; node context (node-test harness, or any non-Electron host) `(js/require
;; "electron")` throws "Cannot find module 'electron'". Guarding to nil lets the
;; broker namespace LOAD anywhere (node-core capabilities still work); the
;; electron accessors are only ever dereferenced in the Electron renderer.
;; Centralising this here is the payoff of the M1 seam — one guarded spot, not N.
(def electron "Electron renderer module (ipcRenderer, clipboard, shell, webFrame)."
  (try (js/require "electron") (catch :default _ nil)))

;; --- electron main, via @electron/remote -----------------------------------
(def remote "electron/remote proxy to main-process modules (app, screen, dialog, Menu)."
  (try (js/require "@electron/remote") (catch :default _ nil)))
