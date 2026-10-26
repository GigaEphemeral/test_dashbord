// lab-mysql-cluster bootstrap for MySQL Shell (AdminAPI, JavaScript).
//
// Invoked by cluster-init-entrypoint.sh as:
//   mysqlsh --js --no-log --file "$ASSET_DIR/create-cluster.js"
// (no --uri on the command line: credentials are read from the environment inside
// this file, so nothing sensitive lands in the process list or in docker logs).
//
// Responsibilities (the *shape* of the cluster only; accounts and grants are a
// separate SQL step so each half can be re-run independently):
//   1. idempotency - if the cluster already exists, only missing members are added
//   2. configure every instance (tolerant: our static settings come from
//      conf/node.cnf.tpl, so this mostly reports "configurable-opcodes applied")
//   3. dba.createCluster() on the ONE seed instance - bootstrapping the group twice
//      is the classic Docker mistake that leaves two disjoint groups
//   4. cluster.addInstance() for the remaining instances, with a per-instance
//      localAddress so GR announces a name the peers can resolve
//   5. wait until every member reports ONLINE
//
// It never prints a password.

// --- environment -----------------------------------------------------------
function env(name, fallback) {
  var v = (typeof process !== 'undefined' && process.env) ? process.env[name] : undefined;
  if (v === undefined || v === null || v === '') {
    return fallback;
  }
  return String(v);
}

if (typeof process === 'undefined' || !process.env) {
  throw new Error(
    'MySQL Shell JS has no process.env - create-cluster.js cannot read credentials ' +
    'from the environment. Check the mysqlsh version (MYSQL_HA_INIT_IMAGE).');
}

var ADMIN_USER = env('MYSQL_HA_ADMIN_USER', 'root');
var ADMIN_PASSWORD = env('MYSQL_ROOT_PASSWORD', null);
var NODE_PORT = parseInt(env('MYSQL_HA_NODE_PORT', '3306'), 10);
var GR_PORT = parseInt(env('MYSQL_HA_GR_LOCAL_PORT', '33061'), 10);
var SEED_HOST = env('MYSQL_HA_MYSQL_1_HOST', 'mysql-1');
var HOSTS = [
  env('MYSQL_HA_MYSQL_1_HOST', 'mysql-1'),
  env('MYSQL_HA_MYSQL_2_HOST', 'mysql-2'),
  env('MYSQL_HA_MYSQL_3_HOST', 'mysql-3'),
];
var EXPECTED_MEMBERS = parseInt(env('MYSQL_HA_EXPECTED_MEMBERS', '3'), 10);
var CLUSTER_NAME = env('MYSQL_HA_CLUSTER_NAME', 'labCluster');
var ACCOUNT_HOST_PATTERN = env('MYSQL_HA_ACCOUNT_HOST_PATTERN', '%');
var RECOVERY_METHOD = env('MYSQL_HA_RECOVERY_METHOD', 'clone');
var WAIT_SYNC_TIMEOUT = parseInt(env('MYSQL_HA_WAIT_SYNC_TIMEOUT_SECONDS', '60'), 10);
var READY_TIMEOUT_MS = parseInt(env('MYSQL_HA_CLUSTER_READY_TIMEOUT_SECONDS', '180'), 10) * 1000;
var POLL_INTERVAL_MS = parseInt(env('MYSQL_HA_CLUSTER_POLL_INTERVAL_SECONDS', '5'), 10) * 1000;
var CONFIGURE_INSTANCES = env('MYSQL_HA_CONFIGURE_INSTANCES', 'true').toLowerCase() === 'true';
// The group name lives in exactly one place (this env key, also rendered into
// conf/node.cnf.tpl). Handing it to createCluster() too keeps the runtime value and
// the option file identical, so a node restarted into an existing group cannot join
// a differently named group; with an explicit UUID MySQL Shell will not invent one.
var CLUSTER_GROUP_NAME = env('MYSQL_HA_CLUSTER_GROUP_NAME', '');
// Escape hatch for shell options this file does not model, e.g.
// MYSQL_HA_CREATE_CLUSTER_EXTRA_OPTS_JSON='{"autoRejoinTries":5}'
var EXTRA_OPTS = JSON.parse(env('MYSQL_HA_CREATE_CLUSTER_EXTRA_OPTS_JSON', '{}'));

if (!ADMIN_PASSWORD) {
  throw new Error('MYSQL_ROOT_PASSWORD is not set in the init container environment.');
}

// A UUID is what Group Replication demands; catching the shape here gives a far
// better message than the one mysqld/Shell produce.
if (CLUSTER_GROUP_NAME && !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(CLUSTER_GROUP_NAME)) {
  throw new Error('MYSQL_HA_CLUSTER_GROUP_NAME must be a UUID (e.g. the value produced by ' +
    '`mysqlsh --js -e "print(util.generateUuid())"`), got: ' + CLUSTER_GROUP_NAME);
}

function conn(host) {
  return { user: ADMIN_USER, password: ADMIN_PASSWORD, host: host, port: NODE_PORT };
}

// addInstance()/getCluster() accept a connection dictionary on current shells and
// a plain URI on older ones; dict first, URI with the embedded password second
// (still never argv, and MySQL Shell masks passwords when it echoes a URI).
function connUri(host) {
  return ADMIN_USER + '@' + host + ':' + NODE_PORT;
}

// Second attempt for shells that reject the connection dictionary. The password
// is only ever in this in-memory string (never argv); MySQL Shell masks it when
// it echoes a URI.
function connUriWithPassword(host) {
  return ADMIN_USER + ':' + ADMIN_PASSWORD + '@' + host + ':' + NODE_PORT;
}

function redacted(host) {
  return connUri(host);
}

function localAddressOf(host) {
  return host + ':' + GR_PORT;
}

function sleep(ms) {
  // util.sleep() is MySQL Shell's documented blocking helper and takes seconds.
  util.sleep(Math.max(1, Math.floor(ms / 1000)));
}

function instanceOpts(host) {
  return Object.assign({
    localAddress: localAddressOf(host),
    interactive: false,
    accountHostPattern: ACCOUNT_HOST_PATTERN,
    waitSyncTimeout: WAIT_SYNC_TIMEOUT,
  }, EXTRA_OPTS);
}

// --- preflight -------------------------------------------------------------
// dba.checkInstanceConfiguration() keys have been reported in both snake_case and
// camelCase across shell releases, so read whichever exists instead of guessing.
function pick(obj) {
  var i, key, camel;
  for (i = 1; i < arguments.length; i += 1) {
    key = arguments[i];
    if (obj && obj[key] !== undefined) {
      return obj[key];
    }
    camel = key.replace(/_([a-z])/g, function (m, c) { return c.toUpperCase(); });
    if (obj && obj[camel] !== undefined) {
      return obj[camel];
    }
  }
  return undefined;
}

function listOf(v) {
  if (v === undefined || v === null) { return []; }
  return Array.isArray(v) ? v : [v];
}

function checkInstances() {
  var problems = [];
  HOSTS.forEach(function (host) {
    var check;
    try {
      check = dba.checkInstanceConfiguration(conn(host));
    } catch (e) {
      throw new Error('cannot reach ' + redacted(host) + ' for a configuration check: ' +
        e.message + ' - is mysqld up and is the node healthy? (db-cluster-status.sh)');
    }
    var errors = listOf(pick(check, 'errors'));
    var restart = listOf(pick(check, 'restart_required_opcodes', 'restart_parameters'));
    var configurable = listOf(pick(check, 'configurable_opcodes'));
    if (errors.length) {
      problems.push(redacted(host) + ' errors: ' + errors.join('; '));
    }
    if (restart.length) {
      problems.push(redacted(host) + ' needs restart-only settings that ' +
        'conf/node.cnf.tpl must provide: ' + restart.join(', '));
    }
    if (configurable.length) {
      println('preflight: ' + redacted(host) + ' has ' + configurable.length +
        ' dynamically configurable change(s)');
    }
  });
  if (problems.length) {
    println('WARN: instance(s) not fully prepared for Group Replication:\n  ' +
      problems.join('\n  ') +
      '\n  (bootstrap continues; dba.createCluster is the authority and will abort ' +
      'if the instance is genuinely unusable)');
  }
}

function configureInstances() {
  HOSTS.forEach(function (host) {
    try {
      dba.configureInstance(conn(host), { interactive: false });
      println('configured ' + redacted(host));
    } catch (e) {
      // Not fatal: every startup-only GR setting is already in the node option
      // file, so a refused SET PERSIST here must not abort the whole bootstrap.
      println('WARN: configureInstance(' + redacted(host) + ') failed: ' + e.message);
    }
  });
}

// --- membership ------------------------------------------------------------
function topologyOf(cluster) {
  var status = cluster.status();
  var topology = (status && status.defaultReplicaSet && status.defaultReplicaSet.topology) || {};
  var rows = [];
  Object.keys(topology).forEach(function (addr) {
    rows.push({
      address: addr,
      role: topology[addr].role || '?',
      status: topology[addr].status || '?',
    });
  });
  return { clusterState: status ? status.clusterStatus : 'UNKNOWN', members: rows };
}

function onlineCount(rows) {
  var n = 0;
  rows.forEach(function (r) { if (r.status === 'ONLINE') { n += 1; } });
  return n;
}

function primaryOf(rows) {
  var n = 0;
  rows.forEach(function (r) { if (r.role === 'PRIMARY') { n += 1; } });
  return n;
}

function addMissingMembers(cluster) {
  var seen = JSON.stringify(topologyOf(cluster).members);
  HOSTS.forEach(function (host) {
    if (seen.indexOf(host + ':' + NODE_PORT) !== -1) {
      println('already a member: ' + redacted(host));
      return;
    }
    println('adding ' + redacted(host) + ' (recoveryMethod=' + RECOVERY_METHOD + ')');
    var opts = instanceOpts(host);
    opts.recoveryMethod = RECOVERY_METHOD;
    try {
      cluster.addInstance(conn(host), opts);
    } catch (dictError) {
      // Older/newer shells differ on whether addInstance accepts a connection
      // dictionary; retry with a URI string before giving up.
      try {
        cluster.addInstance(connUriWithPassword(host), opts);
      } catch (uriError) {
        throw new Error('addInstance(' + redacted(host) + ') failed as dictionary: ' +
          dictError.message + ' / as URI: ' + uriError.message +
          ' - errant transactions left by each node\'s own initdb are cured by ' +
          'MYSQL_HA_RECOVERY_METHOD=clone (the default here); a seed/port mismatch ' +
          'is the other usual cause.');
      }
    }
  });
}

function waitForOnline(cluster) {
  var deadline = Date.now() + READY_TIMEOUT_MS;
  var last = null;
  while (true) {
    var view = topologyOf(cluster);
    last = view;
    println('cluster ' + view.clusterState + ': ONLINE ' + onlineCount(view.members) +
      '/' + EXPECTED_MEMBERS + ', PRIMARY count=' + primaryOf(view.members));
    if (view.clusterState === 'ON_NO_QUORUM' || view.clusterState === 'ERROR') {
      throw new Error('cluster reported ' + view.clusterState + '; members: ' +
        JSON.stringify(view.members));
    }
    if (onlineCount(view.members) >= EXPECTED_MEMBERS) {
      return view;
    }
    if (Date.now() >= deadline) {
      throw new Error('cluster did not reach ' + EXPECTED_MEMBERS +
        ' ONLINE members within ' + (READY_TIMEOUT_MS / 1000) + 's; last view: ' +
        JSON.stringify(view.members) +
        ' - RECOVERING members usually mean clone is still running (watch ' +
        'docker logs jl-mysql-<n>).');
    }
    sleep(POLL_INTERVAL_MS);
  }
}

// --- main ------------------------------------------------------------------
(function main() {
  println('lab-mysql-cluster bootstrap: seed=' + redacted(SEED_HOST) +
    ' cluster=' + CLUSTER_NAME + ' members=' + HOSTS.join(','));

  checkInstances();
  if (CONFIGURE_INSTANCES) {
    configureInstances();
  }

  shell.connect(conn(SEED_HOST));

  var cluster = null;
  try {
    // Positional name, per the AdminAPI signature getCluster(clusterName[, options]).
    cluster = dba.getCluster(CLUSTER_NAME);
    println('cluster "' + CLUSTER_NAME + '" already exists - re-running membership only');
  } catch (e) {
    cluster = null;
  }

  if (!cluster) {
    try {
      cluster = dba.getCluster();
    } catch (e) {
      cluster = null;
    }
  }

  if (!cluster) {
    println('creating cluster "' + CLUSTER_NAME + '" on ' + redacted(SEED_HOST) +
      ' (single-primary)');
    cluster = dba.createCluster(CLUSTER_NAME, Object.assign({
      localAddress: localAddressOf(SEED_HOST),
      multiPrimary: false,
      interactive: false,
      accountHostPattern: ACCOUNT_HOST_PATTERN,
      waitSyncTimeout: WAIT_SYNC_TIMEOUT,
    }, CLUSTER_GROUP_NAME ? { groupName: CLUSTER_GROUP_NAME } : {}, EXTRA_OPTS));
  }

  addMissingMembers(cluster);
  var view = waitForOnline(cluster);

  // Final rescan so Router metadata (M3-2b) sees exactly the current instances.
  try {
    cluster.rescan({ addInstances: false, interactive: false });
  } catch (e) {
    println('WARN: rescan failed (non-fatal): ' + e.message);
  }

  println('BOOTSTRAP OK: clusterState=' + view.clusterState +
    ' ONLINE=' + onlineCount(view.members) + '/' + EXPECTED_MEMBERS +
    ' primary=' + JSON.stringify(view.members.filter(function (m) {
      return m.role === 'PRIMARY';
    })));
})();
