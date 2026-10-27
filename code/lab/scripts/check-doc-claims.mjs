#!/usr/bin/env node
/**
 * Zero-dependency documentation claim checker.
 *
 * Reads code/lab/docclaims/claims.yml (a small YAML subset) and verifies each
 * claim against the repository. It never starts Docker, Maven, npm or the
 * network; the only side effect is a read-only `shell` command when a claim
 * uses that verification kind.
 *
 * Exit codes:
 *   0  every claim passed
 *   1  at least one FAIL / STALE / ERROR
 *   2  bad arguments or an invalid registry
 *
 * Usage:
 *   node code/lab/scripts/check-doc-claims.mjs
 *   node code/lab/scripts/check-doc-claims.mjs --json
 *   node code/lab/scripts/check-doc-claims.mjs --only 'd?-*'
 *   node code/lab/scripts/check-doc-claims.mjs --root . --registry code/lab/docclaims/claims.yml
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_ROOT = path.resolve(SCRIPT_DIR, '../../..');
const DEFAULT_REGISTRY = 'code/lab/docclaims/claims.yml';
const SKIP_DIRS = new Set([
  '.git', '.idea', '.qoder', '.gradle', '.mvn',
  'node_modules', 'target', 'dist', 'build', 'out', 'coverage',
]);

const CLAIM_KEYS = new Set(['id', 'doc', 'statement', 'verify', 'expect', 'polarity', 'last_verified', 'note']);
const VERIFY_KEYS = new Set(['kind', 'path', 'pattern', 'command', 'timeout_ms']);

function usage() {
  return [
    'Usage: node code/lab/scripts/check-doc-claims.mjs [options]',
    '',
    'Options:',
    '  --registry <path>  Registry file (default: code/lab/docclaims/claims.yml;',
    '                     env DOC_CLAIMS_REGISTRY)',
    '  --root <path>      Repository root (default: three levels above this script;',
    '                     env REPO_ROOT)',
    '  --only <glob>      Run only claim ids matching the glob (* and ? supported)',
    '  --json             Machine-readable output (nothing else on stdout)',
    '  -h, --help         Show this help',
  ].join('\n');
}

function parseArgs(argv) {
  const opts = {
    root: process.env.REPO_ROOT || DEFAULT_ROOT,
    registry: process.env.DOC_CLAIMS_REGISTRY || '',
    json: false,
    only: null,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      i += 1;
      if (i >= argv.length) throw new Error(`${arg} requires a value`);
      return argv[i];
    };
    if (arg === '--json') opts.json = true;
    else if (arg === '-h' || arg === '--help') opts.help = true;
    else if (arg === '--root') opts.root = next();
    else if (arg === '--registry') opts.registry = next();
    else if (arg === '--only') opts.only = next();
    else if (arg.startsWith('--root=')) opts.root = arg.slice('--root='.length);
    else if (arg.startsWith('--registry=')) opts.registry = arg.slice('--registry='.length);
    else if (arg.startsWith('--only=')) opts.only = arg.slice('--only='.length);
    else throw new Error(`unknown argument: ${arg}`);
  }
  opts.root = path.resolve(opts.root);
  opts.registry = opts.registry
    ? path.resolve(opts.root, opts.registry)
    : path.join(opts.root, DEFAULT_REGISTRY);
  if (opts.only === '') throw new Error('--only requires a non-empty glob');
  return opts;
}

function usageError(message) {
  const error = new Error(message);
  error.usage = true;
  throw error;
}

// ---------------------------------------------------------------------------
// YAML subset parser
//
// Supports what the registry needs: a top-level list of maps, nested maps,
// block lists, inline [a, b] lists, quoted/plain scalars, comments, booleans
// and numbers. Two-space indentation only. No anchors, block scalars or flow
// maps. Unknown structure is rejected rather than guessed.
// ---------------------------------------------------------------------------

function stripComment(line) {
  let quote = null;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (quote) {
      if (ch === '\\' && quote === '"') i += 1;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") quote = ch;
    else if (ch === '#' && (i === 0 || /\s/.test(line[i - 1]))) return line.slice(0, i);
  }
  return line;
}

function findColon(text) {
  let quote = null;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (quote) {
      if (ch === '\\' && quote === '"') i += 1;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") quote = ch;
    else if (ch === ':' && (i === text.length - 1 || /\s/.test(text[i + 1]))) return i;
  }
  return -1;
}

function unescapeDouble(value) {
  return value.replace(/\\(["\\/bfnrt])/g, (match, ch) => ({
    '"': '"', '\\': '\\', '/': '/', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t',
  }[ch]));
}

function splitInlineList(value) {
  const items = [];
  let current = '';
  let quote = null;
  for (let i = 0; i < value.length; i += 1) {
    const ch = value[i];
    if (quote) {
      current += ch;
      if (ch === '\\' && quote === '"') {
        i += 1;
        current += value[i] ?? '';
      } else if (ch === quote) {
        quote = null;
      }
    } else if (ch === '"' || ch === "'") {
      quote = ch;
      current += ch;
    } else if (ch === ',') {
      if (current.trim()) items.push(current.trim());
      current = '';
    } else {
      current += ch;
    }
  }
  if (current.trim()) items.push(current.trim());
  return items;
}

function parseScalar(raw) {
  const value = raw.trim();
  if (!value) return null;
  if (value.startsWith('[') && value.endsWith(']')) {
    return splitInlineList(value.slice(1, -1)).map(parseScalar);
  }
  if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
    return unescapeDouble(value.slice(1, -1));
  }
  if (value.startsWith("'") && value.endsWith("'") && value.length >= 2) {
    return value.slice(1, -1).replace(/''/g, "'");
  }
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null' || value === '~') return null;
  if (/^-?\d+$/.test(value)) return Number(value);
  if (/^-?\d+\.\d+$/.test(value)) return Number(value);
  return value;
}

function parseYaml(text) {
  const tokens = [];
  const rawLines = text.replace(/^\uFEFF/, '').split(/\r?\n/);
  for (let lineNo = 0; lineNo < rawLines.length; lineNo += 1) {
    const withoutComment = stripComment(rawLines[lineNo]);
    if (!withoutComment.trim()) continue;
    const leading = withoutComment.match(/^[ \t]*/)[0];
    if (leading.includes('\t')) {
      throw new Error(`line ${lineNo + 1}: tabs are not supported for indentation`);
    }
    tokens.push({
      indent: leading.length,
      text: withoutComment.trim(),
      line: lineNo + 1,
    });
  }
  if (!tokens.length) return null;

  let index = 0;

  function parseBlock(indent) {
    if (index >= tokens.length || tokens[index].indent < indent) return null;
    const blockIndent = tokens[index].indent;
    if (tokens[index].text === '-' || tokens[index].text.startsWith('- ')) {
      return parseArray(blockIndent);
    }
    return parseMap(blockIndent);
  }

  function parseNested(parentIndent) {
    if (index < tokens.length && tokens[index].indent > parentIndent) {
      return parseBlock(tokens[index].indent);
    }
    return null;
  }

  function parseMap(indent) {
    const map = {};
    while (index < tokens.length && tokens[index].indent === indent && !tokens[index].text.startsWith('-')) {
      const token = tokens[index];
      const colon = findColon(token.text);
      if (colon <= 0) throw new Error(`line ${token.line}: expected "key: value"`);
      const key = token.text.slice(0, colon).trim();
      const rest = token.text.slice(colon + 1).trim();
      if (Object.prototype.hasOwnProperty.call(map, key)) {
        throw new Error(`line ${token.line}: duplicate key "${key}"`);
      }
      index += 1;
      map[key] = rest ? parseScalar(rest) : parseNested(indent);
    }
    return map;
  }

  function parseArray(indent) {
    const list = [];
    while (index < tokens.length && tokens[index].indent === indent
      && (tokens[index].text === '-' || tokens[index].text.startsWith('- '))) {
      const token = tokens[index];
      if (token.text === '-') {
        index += 1;
        list.push(parseNested(indent));
        continue;
      }
      const content = token.text.slice(2).trim();
      const colon = findColon(content);
      if (colon <= 0) {
        index += 1;
        list.push(parseScalar(content));
        continue;
      }
      const itemIndent = indent + 2;
      const map = {};
      const firstKey = content.slice(0, colon).trim();
      const firstRest = content.slice(colon + 1).trim();
      index += 1;
      map[firstKey] = firstRest ? parseScalar(firstRest) : parseNested(itemIndent);
      while (index < tokens.length && tokens[index].indent === itemIndent
        && !tokens[index].text.startsWith('-')) {
        const entry = tokens[index];
        const entryColon = findColon(entry.text);
        if (entryColon <= 0) throw new Error(`line ${entry.line}: expected "key: value"`);
        const key = entry.text.slice(0, entryColon).trim();
        const rest = entry.text.slice(entryColon + 1).trim();
        if (Object.prototype.hasOwnProperty.call(map, key)) {
          throw new Error(`line ${entry.line}: duplicate key "${key}"`);
        }
        index += 1;
        map[key] = rest ? parseScalar(rest) : parseNested(itemIndent);
      }
      list.push(map);
    }
    return list;
  }

  const document = parseBlock(tokens[0].indent);
  if (index < tokens.length) {
    throw new Error(`line ${tokens[index].line}: unexpected indentation`);
  }
  return document;
}

// ---------------------------------------------------------------------------
// Registry validation
// ---------------------------------------------------------------------------

function assertString(value, label) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${label} must be a non-empty string`);
  }
  return value;
}

function validateClaim(raw, position) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new Error(`claim #${position + 1} must be a map`);
  }
  const unknown = Object.keys(raw).filter((key) => !CLAIM_KEYS.has(key));
  if (unknown.length) {
    throw new Error(`claim #${position + 1} has unknown key(s): ${unknown.join(', ')}`);
  }

  const id = assertString(raw.id, `claim #${position + 1} id`);
  if (!/^[a-z0-9][a-z0-9-]*$/.test(id)) {
    throw new Error(`claim id "${id}" must use lower-case letters, digits and dashes`);
  }
  const statement = assertString(raw.statement, `claim ${id} statement`);

  const polarity = raw.polarity ?? 'positive';
  if (polarity !== 'positive' && polarity !== 'negative') {
    throw new Error(`claim ${id} polarity must be "positive" or "negative"`);
  }

  const docs = raw.doc == null ? [] : (Array.isArray(raw.doc) ? raw.doc : [raw.doc]);
  if (!docs.every((doc) => typeof doc === 'string' && doc.trim())) {
    throw new Error(`claim ${id} doc must be a path or a list of paths`);
  }

  const lastVerified = assertString(raw.last_verified, `claim ${id} last_verified`);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(lastVerified)) {
    throw new Error(`claim ${id} last_verified must be YYYY-MM-DD`);
  }

  const verify = raw.verify;
  if (!verify || typeof verify !== 'object' || Array.isArray(verify)) {
    throw new Error(`claim ${id} verify must be a map`);
  }
  const unknownVerify = Object.keys(verify).filter((key) => !VERIFY_KEYS.has(key));
  if (unknownVerify.length) {
    throw new Error(`claim ${id} verify has unknown key(s): ${unknownVerify.join(', ')}`);
  }

  const kind = assertString(verify.kind, `claim ${id} verify.kind`);
  if (!['file_exists', 'file_contains', 'grep_count', 'shell'].includes(kind)) {
    throw new Error(`claim ${id} verify.kind "${kind}" is not supported`);
  }

  if (kind === 'shell') {
    assertString(verify.command, `claim ${id} verify.command`);
    if (verify.timeout_ms != null
      && (!Number.isInteger(verify.timeout_ms) || verify.timeout_ms <= 0 || verify.timeout_ms > 60000)) {
      throw new Error(`claim ${id} verify.timeout_ms must be an integer between 1 and 60000`);
    }
  } else {
    assertString(verify.path, `claim ${id} verify.path`);
    if (kind === 'file_contains' || kind === 'grep_count') {
      assertString(verify.pattern, `claim ${id} verify.pattern`);
      try {
        // Compile once here so a bad regex is a registry error, not a claim error.
        new RegExp(verify.pattern);
      } catch (error) {
        throw new Error(`claim ${id} verify.pattern is not a valid RegExp: ${error.message}`);
      }
    }
  }

  const expect = raw.expect;
  if (kind === 'grep_count') {
    if (!Number.isInteger(expect) || expect < 0) {
      throw new Error(`claim ${id} expect must be a non-negative integer for grep_count`);
    }
  } else if (typeof expect !== 'boolean') {
    throw new Error(`claim ${id} expect must be true or false for ${kind}`);
  }

  if (polarity === 'negative') {
    const valid = typeof expect === 'number' ? expect === 0 : expect === false;
    if (!valid) throw new Error(`claim ${id} is negative, so expect must be false or 0`);
  } else {
    const valid = typeof expect === 'number' ? expect > 0 : expect === true;
    if (!valid) throw new Error(`claim ${id} is positive, so expect must be true or a count > 0`);
  }

  return {
    id,
    statement,
    polarity,
    docs,
    lastVerified,
    verify,
    expect,
    note: typeof raw.note === 'string' ? raw.note : null,
  };
}

function loadRegistry(file) {
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch (error) {
    throw new Error(`cannot read registry ${file}: ${error.message}`);
  }
  const document = parseYaml(text);
  if (!Array.isArray(document)) {
    throw new Error('registry must be a top-level YAML list of claims');
  }
  const ids = new Set();
  const claims = document.map((raw, position) => {
    const claim = validateClaim(raw, position);
    if (ids.has(claim.id)) throw new Error(`duplicate claim id: ${claim.id}`);
    ids.add(claim.id);
    return claim;
  });
  if (!claims.length) throw new Error('registry contains no claims');
  return claims;
}

// ---------------------------------------------------------------------------
// Verification
// ---------------------------------------------------------------------------

function resolveInsideRoot(root, relativePath, label) {
  const absolute = path.resolve(root, relativePath);
  const relativeToRoot = path.relative(root, absolute);
  if (relativeToRoot.startsWith('..') || path.isAbsolute(relativeToRoot)) {
    throw new Error(`${label} escapes the repository root: ${relativePath}`);
  }
  return absolute;
}

function collectFiles(target) {
  const stat = fs.statSync(target);
  if (stat.isFile()) return [target];
  if (!stat.isDirectory()) return [];
  const files = [];
  const entries = fs.readdirSync(target, { withFileTypes: true })
    .sort((a, b) => a.name.localeCompare(b.name));
  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      files.push(...collectFiles(path.join(target, entry.name)));
    } else if (entry.isFile()) {
      files.push(path.join(target, entry.name));
    }
  }
  return files;
}

function runShell(command, cwd, timeoutMs) {
  let lastError = null;
  for (const shell of ['bash', 'sh']) {
    const result = spawnSync(shell, ['-c', command], {
      cwd,
      timeout: timeoutMs ?? 10000,
      encoding: 'utf8',
      windowsHide: true,
    });
    if (result.error && result.error.code === 'ENOENT') {
      lastError = result.error;
      continue;
    }
    if (result.error) {
      return {
        ok: false,
        code: result.error.code || 'error',
        detail: result.error.message.split('\n')[0],
      };
    }
    return {
      ok: result.status === 0,
      code: result.status,
      detail: result.status === 0 ? 'exit 0' : `exit ${result.status}`,
    };
  }
  return { ok: false, code: 'no-shell', detail: lastError ? lastError.message : 'bash/sh not found' };
}

function verifyClaim(claim, root) {
  const verify = claim.verify;
  try {
    if (verify.kind === 'file_exists') {
      const target = resolveInsideRoot(root, verify.path, `claim ${claim.id} path`);
      let exists = false;
      try {
        exists = fs.statSync(target).isFile();
      } catch {
        exists = false;
      }
      return { actual: exists, detail: `${verify.kind} ${verify.path} -> ${exists}` };
    }

    if (verify.kind === 'file_contains') {
      const target = resolveInsideRoot(root, verify.path, `claim ${claim.id} path`);
      let text;
      try {
        text = fs.readFileSync(target, 'utf8');
      } catch (error) {
        return {
          actual: false,
          detail: `${verify.kind} ${verify.path} pattern=${JSON.stringify(verify.pattern)} -> false (${error.code || error.message})`,
        };
      }
      const found = new RegExp(verify.pattern).test(text);
      return {
        actual: found,
        detail: `${verify.kind} ${verify.path} pattern=${JSON.stringify(verify.pattern)} -> ${found}`,
      };
    }

    if (verify.kind === 'grep_count') {
      const target = resolveInsideRoot(root, verify.path, `claim ${claim.id} path`);
      let files;
      try {
        files = collectFiles(target);
      } catch (error) {
        throw new Error(`grep_count path ${verify.path}: ${error.code || error.message}`);
      }
      const regex = new RegExp(verify.pattern);
      let count = 0;
      let matchedFiles = 0;
      for (const file of files) {
        let text;
        try {
          text = fs.readFileSync(file, 'utf8');
        } catch {
          continue;
        }
        if (text.includes('\0')) continue;
        let fileCount = 0;
        for (const line of text.split(/\r?\n/)) {
          if (regex.test(line)) fileCount += 1;
        }
        if (fileCount) {
          count += fileCount;
          matchedFiles += 1;
        }
      }
      return {
        actual: count,
        detail: `${verify.kind} ${verify.path} pattern=${JSON.stringify(verify.pattern)} -> ${count} matching line(s) in ${matchedFiles} file(s)`,
      };
    }

    if (verify.kind === 'shell') {
      const result = runShell(verify.command, root, verify.timeout_ms);
      return {
        actual: result.ok,
        detail: `shell "${verify.command}" -> ${result.detail}`,
      };
    }

    throw new Error(`unsupported kind: ${verify.kind}`);
  } catch (error) {
    return { actual: null, error: error.message };
  }
}

function matchesExpect(claim, actual) {
  if (typeof claim.expect === 'number') {
    return claim.expect === 0 ? actual === 0 : actual >= claim.expect;
  }
  return actual === claim.expect;
}

function statusFor(claim, result) {
  if (result.error || result.actual === null) return 'ERROR';
  if (matchesExpect(claim, result.actual)) return 'PASS';
  return claim.polarity === 'negative' ? 'STALE' : 'FAIL';
}

function globToRegExp(glob) {
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*/g, '.*')
    .replace(/\?/g, '.');
  return new RegExp(`^${escaped}$`);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function main(argv) {
  let opts;
  try {
    opts = parseArgs(argv);
  } catch (error) {
    process.stderr.write(`ERROR ${error.message}\n\n${usage()}\n`);
    return 2;
  }

  if (opts.help) {
    process.stdout.write(`${usage()}\n`);
    return 0;
  }

  let claims;
  try {
    claims = loadRegistry(opts.registry);
  } catch (error) {
    if (opts.json) {
      process.stdout.write(`${JSON.stringify({
        tool: 'check-doc-claims',
        error: error.message,
        exit_code: 2,
      }, null, 2)}\n`);
    } else {
      process.stderr.write(`ERROR ${error.message}\n`);
    }
    return 2;
  }

  let selected = claims;
  if (opts.only) {
    const matcher = globToRegExp(opts.only);
    selected = claims.filter((claim) => matcher.test(claim.id));
    if (!selected.length) {
      const message = `--only "${opts.only}" matched no claim id`;
      if (opts.json) {
        process.stdout.write(`${JSON.stringify({ tool: 'check-doc-claims', error: message, exit_code: 2 }, null, 2)}\n`);
      } else {
        process.stderr.write(`ERROR ${message}\n`);
      }
      return 2;
    }
  }

  const results = selected.map((claim) => {
    const result = verifyClaim(claim, opts.root);
    const status = statusFor(claim, result);
    let detail = result.detail || '';
    if (status === 'FAIL') {
      detail += ` | expected ${JSON.stringify(claim.expect)} but observed ${JSON.stringify(result.actual)}`;
    } else if (status === 'STALE') {
      detail += ` | docs say this is missing, but the verification now matches; update: ${claim.docs.join(', ') || '(no doc path)'}`;
    } else if (status === 'ERROR') {
      detail = result.error || 'verification failed';
    }
    return {
      id: claim.id,
      status,
      polarity: claim.polarity,
      statement: claim.statement,
      docs: claim.docs,
      last_verified: claim.lastVerified,
      expect: claim.expect,
      actual: result.actual,
      detail,
      verify: claim.verify,
      note: claim.note,
    };
  });

  const summary = {
    total: results.length,
    pass: results.filter((item) => item.status === 'PASS').length,
    fail: results.filter((item) => item.status === 'FAIL').length,
    stale: results.filter((item) => item.status === 'STALE').length,
    error: results.filter((item) => item.status === 'ERROR').length,
  };
  const exitCode = summary.fail || summary.stale || summary.error ? 1 : 0;

  if (opts.json) {
    process.stdout.write(`${JSON.stringify({
      tool: 'check-doc-claims',
      repo_root: opts.root,
      registry: opts.registry,
      only: opts.only,
      generated_at: new Date().toISOString(),
      summary: { ...summary, exit_code: exitCode },
      claims: results,
    }, null, 2)}\n`);
    return exitCode;
  }

  console.log('Documentation claim check');
  console.log(`registry : ${opts.registry}`);
  console.log(`repo root: ${opts.root}`);
  if (opts.only) console.log(`only     : ${opts.only}`);
  console.log('');
  for (const item of results) {
    console.log(`${item.status.padEnd(5)} ${item.id}`);
    console.log(`      ${item.detail}`);
    console.log(`      statement: ${item.statement}`);
  }
  console.log('');
  console.log(`Summary: ${summary.total} total, ${summary.pass} PASS, ${summary.fail} FAIL, ${summary.stale} STALE, ${summary.error} ERROR`);
  console.log(exitCode === 0 ? 'Result: OK' : 'Result: FAILED');
  return exitCode;
}

process.exitCode = main(process.argv.slice(2));
