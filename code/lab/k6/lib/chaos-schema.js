/**
 * Chaos scenario schema v1 (pure JavaScript).
 *
 * This module deliberately imports nothing from k6 so the exact same
 * validation runs inside k6 and under plain Node (`node --test`-style checks).
 * `lib/chaos.js` re-exports it for the timeline driver.
 */

export const SCHEMA_VERSION = 1;
export const ALLOWED_ACTIONS = ['kill', 'start', 'restart', 'freeze', 'resume'];
export const TTL_ACTIONS = ['kill', 'freeze'];
export const DEFAULT_TARGET_PATTERN = '^jl-app-[0-9]+$';
export const DATASTORE_TARGET_PATTERN = /(mysql|redis|rabbit|mongo|postgres)/i;
/** Optional per-step teaching block shown by the lab console (现象/根因/处方/预期曲线). */
export const EXPLAIN_FIELDS = ['symptom', 'rootCause', 'fix', 'expectation'];

function fail(source, message) {
  throw new Error(`[k6/chaos] ${source}: ${message}`);
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function requireString(source, field, value) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    fail(source, `${field} must be a non-empty string`);
  }
  return value.trim();
}

function optionalString(source, field, value) {
  if (value === undefined || value === null) {
    return null;
  }
  return requireString(source, field, value);
}

function requirePositiveNumber(source, field, value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    fail(source, `${field} must be a positive number, got ${JSON.stringify(value)}`);
  }
  return value;
}

/** Optional 现象/根因/处方/预期曲线 block; normalizes an empty block to null. */
function optionalExplain(source, label, value) {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isObject(value)) {
    fail(source, `${label}.explain must be an object when present`);
  }
  const block = {};
  let filled = false;
  for (const field of EXPLAIN_FIELDS) {
    const text = optionalString(source, `${label}.explain.${field}`, value[field]);
    block[field] = text;
    filled = filled || text !== null;
  }
  return filled ? Object.freeze(block) : null;
}

/**
 * Validates and normalizes a parsed scenario object.
 *
 * options.source        - label used in error messages (file path)
 * options.targetPattern - RegExp source for allowed container names
 * options.maxTtlSeconds - upper bound for kill/freeze TTL
 *
 * Returns a frozen normalized copy; events are guaranteed sorted ascending.
 */
export function validateScenario(input, options) {
  const opts = options || {};
  const source = opts.source || 'scenario';
  const maxTtl = opts.maxTtlSeconds === undefined ? 600 : opts.maxTtlSeconds;
  if (!Number.isInteger(maxTtl) || maxTtl <= 0) {
    fail(source, `maxTtlSeconds must be a positive integer, got ${JSON.stringify(maxTtl)}`);
  }

  let targetPattern;
  try {
    targetPattern = new RegExp(opts.targetPattern || DEFAULT_TARGET_PATTERN);
  } catch (error) {
    fail(source, `target pattern is not a valid RegExp: ${error.message}`);
  }

  if (!isObject(input)) {
    fail(source, 'root must be a JSON object');
  }
  if (input.schemaVersion !== SCHEMA_VERSION) {
    fail(source, `schemaVersion must be ${SCHEMA_VERSION}, got ${JSON.stringify(input.schemaVersion)}`);
  }

  const id = requireString(source, 'id', input.id);
  const name = requireString(source, 'name', input.name);
  const description = optionalString(source, 'description', input.description);
  let durationHintSeconds = null;
  if (input.durationHintSeconds !== undefined && input.durationHintSeconds !== null) {
    durationHintSeconds = requirePositiveNumber(
      source,
      'durationHintSeconds',
      input.durationHintSeconds,
    );
  }

  if (!Array.isArray(input.events) || input.events.length === 0) {
    fail(source, 'events must be a non-empty array');
  }

  const seenOffsets = {};
  let previousOffset = 0;
  const events = input.events.map((raw, index) => {
    const label = `events[${index}]`;
    if (!isObject(raw)) {
      fail(source, `${label} must be an object`);
    }

    const atSeconds = requirePositiveNumber(source, `${label}.atSeconds`, raw.atSeconds);
    if (atSeconds < previousOffset) {
      fail(source, `${label}.atSeconds=${atSeconds} is out of order (previous ${previousOffset})`);
    }
    previousOffset = atSeconds;

    const action = requireString(source, `${label}.action`, raw.action);
    if (ALLOWED_ACTIONS.indexOf(action) === -1) {
      fail(
        source,
        `${label}.action must be one of ${ALLOWED_ACTIONS.join('/')}, got "${action}"`,
      );
    }

    const target = requireString(source, `${label}.target`, raw.target);
    if (!targetPattern.test(target)) {
      fail(source, `${label}.target "${target}" does not match ${targetPattern}`);
    }
    if (DATASTORE_TARGET_PATTERN.test(target)) {
      fail(source, `${label}.target "${target}" is a datastore; datastore actions are forbidden`);
    }

    const offsetKey = `${atSeconds}:${target}`;
    if (seenOffsets[offsetKey] === true) {
      fail(source, `${label} duplicates atSeconds=${atSeconds} for target "${target}"`);
    }
    seenOffsets[offsetKey] = true;

    let ttlSeconds = null;
    const hasTtl = raw.ttlSeconds !== undefined && raw.ttlSeconds !== null;
    if (TTL_ACTIONS.indexOf(action) !== -1) {
      if (!hasTtl) {
        fail(source, `${label}.ttlSeconds is required for ${action}`);
      }
      if (!Number.isInteger(raw.ttlSeconds) || raw.ttlSeconds <= 0) {
        fail(source, `${label}.ttlSeconds must be a positive integer`);
      }
      if (raw.ttlSeconds > maxTtl) {
        fail(source, `${label}.ttlSeconds=${raw.ttlSeconds} exceeds the ${maxTtl}s ceiling`);
      }
      ttlSeconds = raw.ttlSeconds;
    } else if (hasTtl) {
      fail(source, `${label}.ttlSeconds is only valid for ${TTL_ACTIONS.join('/')}`);
    }

    return Object.freeze({
      atSeconds,
      action,
      target,
      ttlSeconds,
      title: optionalString(source, `${label}.title`, raw.title),
      text: optionalString(source, `${label}.text`, raw.text),
      explain: optionalExplain(source, label, raw.explain),
    });
  });

  return Object.freeze({
    schemaVersion: SCHEMA_VERSION,
    id,
    name,
    description,
    durationHintSeconds,
    events: Object.freeze(events),
  });
}

/** Stable ascending sort; validation already rejects unsorted input. */
export function sortEvents(events) {
  return events
    .map((event, index) => ({ event, index }))
    .sort((left, right) => {
      if (left.event.atSeconds !== right.event.atSeconds) {
        return left.event.atSeconds - right.event.atSeconds;
      }
      return left.index - right.index;
    })
    .map((entry) => entry.event);
}
