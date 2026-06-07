#!/usr/bin/env node
// License gate (addresses the open red-team item: do NOT ship GPL/copyleft into
// the default build, and vet vendored Apache source before pushing to a remote).
//
// Walks every installed node_modules package + the vendored substrate and
// classifies licenses. Strong-copyleft (GPL/AGPL/SSPL/…) FAILS the build;
// weak-copyleft (LGPL/MPL/EPL/CDDL) WARNS; unknown licenses WARN. Permissive
// (MIT/ISC/BSD/Apache/…) pass silently. Run: node scripts/license-scan.cjs
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const NM = path.join(ROOT, 'node_modules');

const DENY = /\b(A?GPL|SSPL|CC-BY-SA|OSL|CPAL|EUPL|RPL|QPL)\b/i; // strong copyleft → fail
const WARN = /\b(LGPL|MPL|EPL|CDDL|MS-RL)\b/i;                   // weak copyleft → warn
const OK = /\b(MIT|ISC|BSD|Apache|0BSD|Unlicense|CC0|WTFPL|Zlib|Python-2|BlueOak|Artistic|PostgreSQL)\b/i;

function licenseOf(pkgJson) {
  const l = pkgJson.license || pkgJson.licenses;
  if (!l) return null;
  if (typeof l === 'string') return l;
  if (Array.isArray(l)) return l.map((x) => x.type || x).join(' OR ');
  return l.type || JSON.stringify(l);
}

function* pkgDirs(dir) {
  if (!fs.existsSync(dir)) return;
  for (const name of fs.readdirSync(dir)) {
    if (name === '.bin' || name === '.cache') continue;
    const full = path.join(dir, name);
    if (name.startsWith('@')) { yield* pkgDirs(full); continue; }
    const pj = path.join(full, 'package.json');
    if (fs.existsSync(pj)) yield full;
    // nested node_modules (hoisting misses)
    yield* pkgDirs(path.join(full, 'node_modules'));
  }
}

const denied = [], warned = [], unknown = [];
let scanned = 0;
for (const dir of pkgDirs(NM)) {
  let pj;
  try { pj = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')); }
  catch { continue; }
  scanned++;
  const lic = licenseOf(pj);
  const id = `${pj.name}@${pj.version} (${lic || 'UNKNOWN'})`;
  if (!lic) { unknown.push(id); continue; }
  if (DENY.test(lic)) denied.push(id);
  else if (OK.test(lic)) { /* permissive */ }
  else if (WARN.test(lic)) warned.push(id);
  else unknown.push(id);
}

// Vendored substrate (source bundled into the build → must be permissive/owned).
const vendor = [];
for (const v of ['sig', 'defport']) {
  const lf = path.join(ROOT, 'vendor', v, 'LICENSE');
  const pv = path.join(ROOT, 'vendor', v, 'PROVENANCE.md');
  let note = 'no LICENSE/PROVENANCE';
  if (fs.existsSync(lf)) note = fs.readFileSync(lf, 'utf8').split('\n').find((x) => x.trim()) || 'LICENSE present';
  else if (fs.existsSync(pv)) note = 'PROVENANCE.md (owned/internal)';
  vendor.push(`vendor/${v}: ${note.trim()}`);
}

console.log(`license-scan: ${scanned} npm packages scanned\n`);
console.log('vendored substrate:');
vendor.forEach((v) => console.log('  ' + v));
if (warned.length) { console.log(`\nWARN — weak copyleft (${warned.length}):`); warned.forEach((w) => console.log('  ' + w)); }
if (unknown.length) { console.log(`\nWARN — unknown license (${unknown.length}):`); unknown.forEach((u) => console.log('  ' + u)); }

if (denied.length) {
  console.error(`\nFAIL — strong copyleft, not allowed in the default build (${denied.length}):`);
  denied.forEach((d) => console.error('  ' + d));
  process.exit(1);
}
console.log('\nlicense-scan: OK (no strong-copyleft dependencies).');
