# EssentialsXS 2.22.1 — Storage Guide

**Known critical issue:** SQL mode currently introduces a main-thread wait during LuckPerms context calculation. A real player login with AuthMe and NotQuests installed stalled Paper and triggered watchdog shutdown. The fix is pending. Treat this source revision as a development checkpoint. See the [correction plan](PLAN-LOGIN-CONTEXT-DEADLOCK.md).

**EssentialsXS** is the storage fork of EssentialsX. The **S** stands for **Storage**. It adds selectable YAML or MySQL/MariaDB persistence for the base plugin and the included extensions.

This document is the installation tutorial, backend reference, permission reference and implementation overview for this fork. Version **2.22.1** identifies this distribution; it does not mean that the storage changes exist in upstream EssentialsX 2.22.1.

## Contents

1. [Distribution and compatibility](#1-distribution-and-compatibility)
2. [Storage scope](#2-storage-scope)
3. [Backend selection and defaults](#3-backend-selection-and-defaults)
4. [Preparing SQL](#4-preparing-sql)
5. [Migrating from YAML to SQL](#5-migrating-from-yaml-to-sql)
6. [Commands and permissions](#6-commands-and-permissions)
7. [Editing SQL configuration](#7-editing-sql-configuration)
8. [Exporting and returning to YAML](#8-exporting-and-returning-to-yaml)
9. [Database organization](#9-database-organization)
10. [Runtime behavior and new interfaces](#10-runtime-behavior-and-new-interfaces)
11. [Implementation conflicts and their resolution](#11-implementation-conflicts-and-their-resolution)
12. [Troubleshooting and acceptance limits](#12-troubleshooting-and-acceptance-limits)
13. [Building and publishing this distribution](#13-building-and-publishing-this-distribution)

## 1. Distribution and compatibility

### Installable files

| JAR | Purpose | Bukkit plugin identifier |
| --- | --- | --- |
| `EssentialsXS-2.22.1.jar` | Base commands, users, economy and shared storage service | `Essentials` |
| `EssentialsXSChat-2.22.1.jar` | Chat formatting configuration | `EssentialsChat` |
| `EssentialsXSProtect-2.22.1.jar` | Protection configuration | `EssentialsProtect` |
| `EssentialsXSAntiBuild-2.22.1.jar` | Build/use restrictions and associated configuration | `EssentialsAntiBuild` |
| `EssentialsXSSpawn-2.22.1.jar` | Spawn settings and saved spawn locations | `EssentialsSpawn` |
| `EssentialsXSGeoIP-2.22.1.jar` | GeoIP settings; geographic database files remain local | `EssentialsGeoIP` |

Install the base JAR and the extensions you need. Remove the corresponding older JARs before installing replacements, keeping backups outside the active plugin directory. Installing both the upstream base plugin and this fork creates duplicate plugin identifiers.

The artifact names use EssentialsXS. Internal plugin identifiers, Java API packages, commands, permissions and data folders retain their EssentialsX-compatible names. In particular, the main data directory is still `plugins/Essentials`, and commands still use `/essentials`. Other plugins can continue to locate `Essentials`; Vault can continue to use the economy API. Renaming a JAR does not rename its Bukkit plugin identifier.

The storage implementation has been exercised on **Java 25 / Paper 26.3**. The base module retains its inherited Java 8 bytecode target; the SQL module targets Java 11+. This is a compilation detail, not a promise that the entire distribution runs on Java 8 or every historical Bukkit/Spigot release. SQL on Java 8 is unsupported. Use Java 25/Paper 26.3 for the documented acceptance environment.

SQL dependencies are included in the base JAR: HikariCP **7.1.0**, MySQL Connector/J **9.7.0**, and MariaDB Connector/J **3.5.10**. No separate database-driver plugin is required. Vault remains optional and separate. All included modules share the base plugin's pool.

This build is a distribution rename and a public-default cleanup of the implemented storage fork. It does not introduce another storage algorithm or game feature beyond that implementation. Report fork-specific problems to the fork's issue tracker, with secrets removed.

## 2. Storage scope

| Data | SQL location | YAML/local counterpart |
| --- | --- | --- |
| Player and NPC identity, names, preferences, moderation state and timestamps | User tables | `Essentials/userdata/*.yml`, user name/UUID indexes |
| Balances | `user_balances` | User profile `money` field |
| Homes, mail, ignores and cooldowns | Collection tables | User profile collections |
| Warps, jails and spawn locations | Shared-state tables | `warps/*.yml`, `jail.yml`, `spawn.yml` |
| Base, Chat, Protect, AntiBuild, Spawn and GeoIP settings | Six configuration tables | Traditional core configuration plus `EssentialsGeoIP/config.yml` |
| Kits, item prices, custom items and random teleport settings | `resource_documents` | `kits.yml`, `kits/*.yml`, `worth.yml`, `custom_items.yml`, `tpr.yml` |
| Custom text, books and text variants | `resource_documents` | Root `.txt` resources |
| Custom translations | `resource_documents` | `messages/*.properties` |

The local `backend` section, environment credentials, upgrade markers, diagnostic reports, backup/export directories and recovery files remain local. GeoIP binary databases remain local and still require the appropriate license/configuration.

**XMPP, Discord and DiscordLink are excluded from SQL integration in this version.** Installing one of those upstream modules does not give it SQL storage through this fork.

## 3. Backend selection and defaults

Edit the **local** `plugins/Essentials/config.yml`. The generated public defaults are:

```yaml
backend:
  type: yaml
  data-set: default
  configuration-profile: default
  instance-id: essentialsxs-local
  mysql:
    engine: mysql
    host: 127.0.0.1
    port: 3306
    database: essentialsxs
    table-prefix: essxs_
    credentials:
      username-env: ESSENTIALSXS_DB_USER
      password-env: ESSENTIALSXS_DB_PASSWORD
    tls:
      mode: disabled
    pool:
      maximum-size: 4
      connection-timeout-ms: 10000
    persistence:
      flush-interval-ms: 1000
      shutdown-timeout-seconds: 30
      startup-timeout-seconds: 120
      preload-max-bytes: 268435456
  configuration:
    source: backend
    refresh: manual
```

These are public defaults, not credentials or an automatically provisioned database. Existing files are not automatically replaced with these values. A config without a `backend` section continues to select YAML.

### Selection matrix

| `backend.type` | `mysql.engine` | `configuration.source` | Result |
| --- | --- | --- | --- |
| `yaml` | Any inactive SQL value | Any inactive SQL value | Traditional local YAML persistence; JDBC is not initialized |
| `mysql` | `mysql` | `backend` | Player/shared state, functional configuration and resources use MySQL |
| `mysql` | `mariadb` | `backend` | Same storage scope through the MariaDB driver |
| `mysql` | Either supported engine | `yaml` | Player/shared state use SQL; functional configuration/resources remain local YAML |

**MariaDB still uses `backend.type: mysql`; select the driver with `backend.mysql.engine: mariadb`.** There is no `backend.type: mariadb` selector.

Changing the backend, engine, connection, prefix, data set, profile or credential environment references requires a controlled server restart. `/essentials reload` does not reconstruct the pool or switch storage engines. There is no automatic fallback from SQL to YAML.

### Option reference

| Option | Meaning |
| --- | --- |
| `type` | `yaml` (default) or `mysql` |
| `data-set` | Logical owner of player and shared-state records |
| `configuration-profile` | Logical owner of configuration revisions and resources |
| `instance-id` | Readable instance label; each process also receives a unique lease identity |
| `engine` | `mysql` or `mariadb` |
| `host`, `port`, `database` | Existing SQL endpoint and schema; the plugin creates tables, not the database itself |
| `table-prefix` | Prepended to all 25 table names; 1–24 lowercase ASCII letters/digits/underscores, starting with a letter |
| `username-env`, `password-env` | Environment variable names resolved in the server process; both must have nonempty values |
| `tls.mode` | `disabled` or `verify-identity`; the latter maps to the driver's identity-verifying mode |
| `maximum-size` | Shared pool size; accepted range 1–32 |
| `connection-timeout-ms` | Pool acquisition timeout; accepted range 1000–120000 ms |
| `flush-interval-ms` | Deferred-save worker interval; accepted range 1–60000 ms |
| `shutdown-timeout-seconds` | Maximum orderly storage shutdown wait; accepted range 1–300 seconds |
| `startup-timeout-seconds` | Bounded initial connection/schema/preload wait; default 120 seconds |
| `preload-max-bytes` | Serialized-document preload budget; default 256 MiB; additional heap-pressure checks also apply |
| `configuration.source` | `backend` or `yaml`; independent of the player-storage choice |
| `configuration.refresh` | Only `manual` is supported |

Use distinct prefixes when complete table isolation is required. Within one prefix, data sets separate player/shared state, while profiles separate configuration/resources. Resources are profile-owned; they are not isolated merely by selecting another data set. A shared profile is not a live multi-server configuration synchronization feature. Use separate profiles for independently administered servers.

## 4. Preparing SQL

1. Back up the existing plugin directory and database, if any.
2. Provision a MySQL or MariaDB database, for example:

   ```sql
   CREATE DATABASE essentialsxs
     CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
   ```

3. Create a dedicated SQL account using your database administration tool. Restrict its host to the Minecraft server. It needs table creation/migration privileges and normal data access: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER`, `INDEX`, and `REFERENCES` within the selected database. Schema migration also uses server advisory locks. A read-only SQL account cannot run this backend.
4. Provide `ESSENTIALSXS_DB_USER` and `ESSENTIALSXS_DB_PASSWORD` in the environment inherited by the server process. Use your service manager, container secret injection or hosting panel's protected environment settings. Setting variables in another terminal does not change an already running server's environment.
5. Set the connection options in the local bootstrap. For a remote connection, configure an appropriate trusted certificate and `tls.mode: verify-identity`. The default loopback/disabled-TLS example is not a remote connection setup.
6. Keep `type: yaml` until the import described below finishes successfully.

Literal `credentials.username` and `credentials.password` entries are rejected. The environment variable names are configurable: existing explicit names remain valid even if they differ from the public defaults. Do not put actual passwords into published config files, screenshots, issue reports or launch arguments.

## 5. Migrating from YAML to SQL

### Existing installation

1. Install the matching base/extensions and start with `backend.type: yaml`.
2. Configure the SQL connection and credentials. Keep the existing data-set/profile/prefix choice stable throughout migration.
3. Enter maintenance: have every player disconnect and prevent new connections while planning the operation. Import commands must be issued from the server console; the migration service blocks joins while the operation is active.
4. Prevalidate:

   ```text
   essentials backend import --dry-run
   essentials backend status
   ```

   The preview validates the local source and computes its manifest. It does not import records or prove that the SQL endpoint is reachable.
5. Run the import:

   ```text
   essentials backend import
   essentials backend status
   ```

6. Wait for `IMPORT:COMPLETE; restart required`. “Backend operation started” is an asynchronous acknowledgement, not a completion message. Check the server log or `backend-operations/core/last-operation.txt` if a console/RCON session ends before the result arrives.
7. Stop the server normally. Change the **local** `backend.type` to `mysql`, then start it again.
8. Check `/essentials backend status`, `/essentials backend check` and the server log before admitting players.
9. Test homes, mail, kits, economy and spawn; reconnect and perform a controlled restart to verify durability on your server.

Import requires an empty target data set, except for an existing matching resumable import. It makes a local backup under `backend-backups/<timestamp>/core`, validates configuration and identity references, stores a checksum manifest, imports in batches and compares reconstructed SQL values with the source before completing. The original local files remain in place as backups.

For an interrupted import, keep YAML selected and repeat the import with the **same unchanged source and target settings**. Recorded progress is resumed. An unrelated populated target or changed manifest is rejected; do not erase migration records to bypass that protection.

Selecting an uninitialized SQL data set while local resources remain pending import is rejected. An empty database is not an instruction to discard local data. A fresh installation that generated local resources should use this same import workflow.

## 6. Commands and permissions

All new commands belong to the existing `/essentials` command tree. In the console, omit `/`.

| Command | Function | Requirements |
| --- | --- | --- |
| `/essentials backend` | Same as `status` | Backend administration permission |
| `/essentials backend status` | Reports YAML/SQL mode; SQL readiness, endpoint identity labels, preload information and pending changes; migration status | Either backend |
| `/essentials backend check` | Flushes pending changes, checks the lease and reconstructs stored documents to detect read/checksum problems | Active SQL |
| `/essentials backend flush` | Waits asynchronously for queued snapshots to be persisted; failures are reported | Active SQL |
| `/essentials backend import --dry-run` | Validates local source and computes its manifest without importing | YAML selected, console, no connected players |
| `/essentials backend import` | Imports/resumes YAML into the configured SQL target with backup and verification | YAML selected, console, no connected players |
| `/essentials backend export` | Flushes SQL and writes a verified YAML export directory | Active SQL, console, no connected players |
| `/essentials backend config stage` | Writes a complete editable functional configuration/resource draft and records its source revision | Active SQL and `configuration.source: backend`; no existing draft directory |
| `/essentials backend config validate` | Validates the draft without publishing it | Same SQL configuration source; draft required |
| `/essentials backend config publish` | Checks the expected revision, publishes transactionally and triggers functional reload | Same SQL configuration source; valid/current draft required |
| `/essentials backend config reload` | Reads/validates the active SQL revision and triggers functional reload | Same SQL configuration source |

Only import has a read-only preview. `export --dry-run` is not a read-only preview in this version; export still writes files. Use the documented export command.

### Permission reference

| Node | Scope | Default |
| --- | --- | --- |
| `essentials.backend` | All backend administration actions above | Operator |
| `essentials.essentials` | Access to the existing parent `/essentials` command | Existing Essentials permission behavior |

For a non-operator administrator, grant **both** nodes through your permission manager. There are no separate `essentials.backend.import`, `.export`, `.flush`, or `.config.*` permission nodes in 2.22.1. Granting `essentials.backend` grants the whole backend administration surface. The console/no-player restriction for import/export still applies, even to operators.

Existing gameplay permission names are unchanged; SQL does not automatically grant `/home`, `/kit`, economy, moderation or spawn permissions. Use the permission definitions in [the base plugin descriptor](../Essentials/src/main/resources/plugin.yml), plus the descriptors for any installed extensions. Bukkit's plugin identifiers and permission namespaces were deliberately retained for compatibility.

### Existing commands with storage-aware behavior

- `/essentials reload`: refreshes the active SQL functional configuration when configuration is stored in the backend; otherwise follows the local YAML reload path. It does not change connection settings.
- `/essentials cleanup`: works through loaded profiles; SQL cleanup publishes user changes on the server thread in bounded batches.
- `/essentials usermap purge`: rejects the legacy file-purge operation in SQL mode, because identity indexes are transactional. Use backend check/export for inspection and backup.
- `/essentials dump`: reads included functional resources through the active storage adapter. Remove private information before sharing any diagnostic dump.
- Existing player commands continue to use their normal syntax and permissions, with in-memory reads and deferred SQL persistence.

## 7. Editing SQL configuration

With `configuration.source: backend`, editing functional values in the live local `config.yml` does not update the active SQL revision. The local file supplies connection/bootstrap settings; SQL supplies functional settings and resources.

1. Run `/essentials backend config stage` and wait for completion.
2. Edit `plugins/Essentials/backend-staging/core/config.yml` and the staged resources. GeoIP's staged configuration is in `backend-staging/EssentialsGeoIP/config.yml`.
3. Keep the full draft: deleting a configuration resource from the draft removes it from the published revision. A draft is a complete replacement, not a patch.
4. Do not add a `backend` section to the draft. Keep `backend-staging/expected-revision.txt` unchanged; it records the revision against which the draft was prepared.
5. Run `/essentials backend config validate` and wait for success. Validation checks YAML structure, known core option types, selected regular expressions and monetary bounds. It is not a complete semantic validator for every item, kit or extension-specific value; review those values too.
6. Run `/essentials backend config publish`. A stale draft or failed validation cannot replace the active revision. Publication also triggers functional reload.
7. Archive/move the entire `backend-staging` directory outside its active location after you finish. It is not automatically removed, and a new stage operation rejects an existing draft directory. Stage a fresh draft for the next edit.

Run configuration operations sequentially and wait for each result. Concurrent publication/reload is rejected while a configuration operation is busy. `config reload` can refresh an already published revision; automatic periodic refresh is not implemented. Direct SQL editing is not an administration interface: it can invalidate checksums, revisions and ownership relationships.

With `configuration.source: yaml`, edit local functional files and use `/essentials reload`. SQL configuration publication commands are unavailable in that mode.

## 8. Exporting and returning to YAML

1. Disconnect players and issue `essentials backend export` from the console while SQL is active.
2. Wait for completion. The export is written to `plugins/Essentials/backend-exports/<timestamp>/core`, with the corresponding GeoIP configuration under the sibling `EssentialsGeoIP` directory.
3. Review the export and its verification result. The exported core configuration intentionally excludes the connection/bootstrap `backend` section.
4. Stop the server normally. Back up the current live local directories.
5. Restore the exported **included functional resources and user/index files** to their traditional locations. Restore the GeoIP configuration separately. Preserve the local bootstrap, GeoIP binaries and other local-only files; do not replace the entire plugin directory blindly.
6. Merge the preserved local `backend` section into the restored `config.yml` and set `backend.type: yaml`.
7. Restart and validate commands/data before reopening the server.

Export does not activate YAML and does not alter the SQL database. Files left behind from the original import become stale while SQL is active; switching to them without a fresh export can restore outdated data. Back up both SQL and local-only files as part of normal operations.

## 9. Database organization

Every name below is a suffix. With the default prefix, `users` becomes `essxs_users`. The catalog contains **25 tables**, including `schema_history`. Tables use InnoDB, parameterized values, validated identifiers, foreign keys and indexes.

### Identity and namespace model

- `data_sets.id` owns users, name indexes, warps, jails, spawns and imports.
- `users.id` is an internal numeric key; the player UUID is stored as `BINARY(16)` and is unique within a data set.
- `config_profiles.id` owns immutable configuration/resource revisions. `active_revision` selects the current revision.
- Collections use stable hashed keys plus explicit order where applicable; the original keys remain available alongside their hashes.
- The bootstrap is excluded from configuration tables. SQL cannot overwrite the connection details required to reach SQL.

### Nine user tables

| Table | Main fields and role |
| --- | --- |
| `users` | `id`, `data_set_id`, binary `uuid`, `name`, nullable `npc`, `revision`, `generation`, `deleted`; unique `(data_set_id,uuid)` |
| `user_state` | One row per user; nullable typed `v_*` scalar columns for preferences, moderation, timestamps and previous/logout locations |
| `user_balances` | `amount_exact` text preserves the exact decimal; nullable `amount_index DECIMAL(65,30)` supports representable values; `rank_eligible`; index on dataset/ranking/value/user |
| `user_names` | Historical/lookup name, SHA-256 `name_hash`, dataset and user reference; unique name hash within a dataset |
| `user_homes` | User, `entry_hash`, original `entry_key`, `ordinal_no`, residual `value_yaml`; typed `n_*` name/world/coordinates |
| `user_mail` | Same ordered-entry structure; typed message, sender name/UUID, legacy/read flags, send/expiry timestamps |
| `user_ignores` | Ordered entries with a normalized string value when representable; residual retained |
| `user_cooldowns` | Ordered keyed kit/command cooldowns, typed numeric value and pattern where representable; pattern flags remain preserved in residual data |
| `user_extras` | One residual document per user, checksum and codec version; preserves unknown fields and non-normalizable structures |

`v_*` column names derive from known paths, replacing punctuation with underscores. Known fields include nickname, mute reason, jail, IP/geolocation, account name, NPC name, teleport settings, god/fly mode, mute/jail/AFK/socialspy states, payment/confirmation preferences, ranking exemptions, timestamps and previous/logout locations. Remaining fields belong to the residual; normalization is not a reason to discard them.

Missing normalized values remain NULL. Values are removed from residual documents only when they can be represented without loss. Unusual YAML types stay in the residual. An absent balance remains absent, and high-precision money is not rounded to fit the SQL projection. World identifiers/names are stored without requiring that world to be loaded. Order is reconstructed from `ordinal_no` rather than incidental database row order.

### Shared state and resources

| Table | Organization |
| --- | --- |
| `warps` | Dataset, resource/entry keys, entry hash, revision, entry format, typed location fields and residual |
| `jails` | Same namespace, with location entries plus a root residual preserving other document fields |
| `spawns` | Same namespace, with spawn-group locations plus a root residual |
| `resource_documents` | Configuration profile/revision, resource hash/key, document, checksum and codec version; dataset column records provenance; uniqueness is profile/revision/resource |

Root residual entries preserve empty documents and unknown shared-state fields. Resource documents belong to the configuration revision, so kits/texts and their settings can be published together.

### Six configuration tables

Each configuration table stores `profile_id`, `revision`, `path_hash`, serialized `path`, `value_type` and `value_yaml`. An option row can hold a structured section; these tables are not a promise of one SQL column per configuration setting.

| Table | Ownership in traditional configuration |
| --- | --- |
| `config_core` | Remaining base options |
| `config_chat` | `chat` section |
| `config_protect` | `protect` section after AntiBuild-owned paths are separated |
| `config_antibuild` | `protect.disable.build/use`, `protect.alert.on-placement/on-use/on-break`, `protect.blacklist.placement/usage/break/piston/dispenser` |
| `config_spawn` | `newbies`, and root options beginning with `respawn-` or `spawn-` |
| `config_geoip` | GeoIP configuration file |

The adapter recomposes the original paths for consumers. Saved spawn **locations** live in `spawns`; spawn **settings** live in `config_spawn`.

### Six coordination and migration tables

| Table | Role |
| --- | --- |
| `data_sets` | Dataset names and initialized/imported state |
| `config_profiles` | Profile names and active revision pointer |
| `config_revisions` | Profile/revision key, checksum, creation time, codec version, published state and author |
| `schema_history` | Idempotent migration-step IDs and checksums |
| `backend_leases` | Dataset owner, fencing generation, expiry and last-commit receipt |
| `import_runs` | Run ID, dataset, manifest hash, state, progress and update timestamp |

User child rows reference users, dataset-scoped records reference their dataset, and configuration/resource rows reference their revision/profile. Cascading user child deletion and revision constraints prevent orphaned records. Tombstones and generations additionally prevent late snapshots from recreating a deleted profile incorrectly.

Migration history evolves through table creation, commit receipts, normalized collection fields, shared-entry formats, checksums/codecs/generations and relationship constraints. Each step records a checksum. A fresh schema creates the complete current structure directly while recording the same migration history; upgrading an existing schema applies missing idempotent steps. Do not edit `schema_history` to suppress a checksum conflict.

## 10. Runtime behavior and new interfaces

### Startup, saves and shutdown

The base storage service loads profiles/indexes before enabling SQL consumers. User reads remain in memory for synchronous Essentials and Vault APIs. Startup loads users in pages of 500 and reads collections in bulk, avoiding one SQL query per player and joins that multiply collection rows. Time/document/heap limits fail startup with a diagnostic instead of admitting users with incomplete data.

JDBC operates on the storage worker. Bukkit objects are published on the server thread. Saves capture detached snapshots when requested, group pending changes and write changed records asynchronously. A successful command normally means the mutation was admitted to memory; it does not mean the database has already committed it. Use `flush` when a persistence barrier is required.

Money transfers capture both users in one transaction. Exact decimal text is authoritative; the SQL numeric projection never replaces it. API signatures are preserved, and Vault registration in SQL mode waits until users/settings/economy are available.

On a detected SQL failure, pending changes are retained and new persistent mutations are suspended. Status becomes degraded. Existing in-memory data is not replaced with invented balances or an automatic YAML fallback. The worker attempts recovery; losing ownership to another writer requires resolving that ownership conflict, not merely reconnecting.

Normal shutdown stops admission, captures final state, waits for prior writes, releases the lease and closes the pool. If completion cannot be confirmed within the configured timeout, an emergency snapshot export is attempted under `backend-recovery/<timestamp>/core`. Those files require manual recovery review; they do not automatically replace SQL data.

### Writer ownership

Only **one active writer per data set** is supported. The lease has a 30-second expiry, is renewed by the worker and is checked with its fencing generation inside write transactions. Two live servers cannot use the same player data set as a supported shared-storage network. A separate instance label does not remove this rule. There is no simultaneous multi-server player synchronization in this version.

### Internal interfaces for contributors

| Interface/component | Responsibility |
| --- | --- |
| `UserRepository` | Asynchronous user-document repository contract |
| `ConfigurationRepository` | Asynchronous configuration/resource repository contract |
| `SharedStateRepository` | Asynchronous shared-state repository contract |
| `DocumentRepository` | `read`, `enumerate`, `write`, `delete`, `flush`; reads distinguish absence with `Optional` |
| `StorageEngine` | Bulk load, transaction commit, heartbeat, schema/import initialization and close |
| `BackendSession` | Availability, cache, dirty/deleted snapshots, generations, atomic units, flush, configuration publication and lifecycle |
| `ConfigurationStore` | Existing Configurate consumer adapter; YAML and cached SQL implementations |
| `ResourceCatalog` | Included-resource ownership, stable keys and path validation |
| `BackendMigration` | Maintenance, import/resume/export and verification |

Repository identifiers and serialized snapshots contain no Bukkit objects. Exceptional asynchronous results distinguish I/O failure from absence; `StorageConflictException` represents revision conflicts. These are internal storage interfaces, not a replacement for the supported Essentials/Vault public APIs.

## 11. Implementation conflicts and their resolution

| Conflict | Resolution and practical effect |
| --- | --- |
| Synchronous Essentials/Vault APIs versus asynchronous JDBC | Preload/cache documents, run JDBC on a worker, publish Bukkit-related objects on the main thread; registration follows readiness |
| Legacy file existence, enumeration, deletion and name indexes | Route included resources through storage adapters and transactional SQL indexes; preserve traditional YAML behavior |
| Queued saves seeing later mutable state | Capture detached snapshots at save request time; preserve coalescing and track failures so a barrier cannot falsely claim success |
| Exact money versus finite SQL decimals | Store exact decimal text and only add a lossless numeric projection; group transfer participants in one transaction |
| Unknown keys and worlds that are not loaded | Normalize representable fields; preserve unknown/unusual data in residual documents and retain world references without resolving Bukkit worlds |
| Mail/cooldown ordering and pattern flags | Store explicit ordinals, keep residual extension fields and serialize pattern flags alongside the expression |
| Old queued writes after deletion/recreation | Use cache generations, SQL revisions and tombstones to reject stale snapshots |
| Two processes writing one dataset | Expiring lease plus per-transaction fencing generation; schema setup also uses an advisory lock |
| MySQL/MariaDB DDL implicit commits | Record checksummed idempotent migration steps; detect an interrupted step and inspect/apply missing schema elements on restart |
| Slow fresh-schema creation through repeated ALTER statements | Create complete current tables for new installations; preserve historical step checksums and incremental upgrades |
| Connection loss after COMMIT but before acknowledgement | Persist a transaction receipt, then resolve uncertain commits before retrying; avoid replaying a completed transfer |
| Configuration split across modules with overlapping legacy paths | Give AntiBuild its explicit path list, assign other sections to one owner, then reconstruct the paths expected by consumers |
| Partial or stale configuration publication | Validate a complete detached revision, compare its expected revision and publish settings/resources transactionally; reject concurrent administration operations |
| Configuration reload overwriting dirty player state | Refresh configuration-owned cache entries only; retain user/shared-state snapshots |
| Pool-size-one import deadlock | Keep import metadata operations on the transaction's existing connection instead of borrowing a second connection |
| Interrupted import and incomplete late name indexes | Checksum the source, persist batch progress, verify reconstruction and reapply the complete name index after profile batches |
| Driver loading after shading | Preserve JDBC service resources, relocate dependency namespaces and avoid driver-breaking minimization |
| Shutdown failure or exhausted wait time | Retain pending snapshots, attempt a local recovery export, cancel the worker and close storage asynchronously when orderly completion is impossible |
| Distribution branding versus ecosystem compatibility | Rename public artifacts/default labels while keeping Bukkit identifiers, public packages, folder names and permission nodes compatible |

## 12. Troubleshooting and acceptance limits

| Symptom | Action |
| --- | --- |
| SQL startup fails with missing credentials | Set the named variables in the environment of the actual server service and restart |
| Local data must be imported | Return to YAML selection, complete the import into the intended empty target, then restart with SQL |
| Another writer owns the data set | Stop the other owner normally; use another data set if appropriate; do not manually bypass fencing |
| `DEGRADED` or flush failure | Restore database access, inspect logs, retain pending/recovery data and wait for a successful flush before considering changes durable |
| Draft already exists | Archive the complete old staging directory, then stage again |
| Draft revision conflict | Stage a fresh copy of the active revision and reapply the intended edits |
| Configuration operation busy | Wait for completion and retry sequentially |
| Local config edits have no effect | Check `configuration.source`; use stage/publish for SQL functional configuration or local reload for YAML |
| Import/export refuses an operator | Use the console with zero connected players; permission alone does not bypass maintenance restrictions |
| GeoIP reports missing license/database | Supply its licensed local binary database; SQL stores options, not the binary |
| RCON says only “operation started” | Check the server log, status and migration report after completion; the client may have disconnected before the callback |
| Plugins disappear after installing JARs | Check the log and duplicate identifiers; keep one base JAR and matching extensions, then restart normally |

Prior implementation validation included 56 core tests, six integration cases on MySQL 8.4.11 and the same six on MariaDB 11.8.6. Cases covered round trips, fencing, revision conflicts/tombstones, interrupted imports, 501-user precaching and lost commit acknowledgements. An actual Paper installation exercised SQL startup, configuration publication/reload, export, Vault provider registration and player home/mail/kit/balance/spawn commands; home/mail persistence was confirmed after reconnect and a full restart.

These are scoped results, not a claim of exhaustive compatibility. Extended outage/load acceptance and complete moderation testing with real players remain follow-up work. GeoIP lookup requires a valid local database. This public packaging change reuses that implementation evidence; it does not turn previously untested scenarios into tested ones.

For version-specific behavior, the authoritative implementation references are [BackendCommand](../Essentials/src/main/java/com/earth2me/essentials/commands/essentials/BackendCommand.java), [BackendOptions](../Essentials/src/main/java/com/earth2me/essentials/config/storage/BackendOptions.java), [SqlTable](../Essentials/src/main/java/com/earth2me/essentials/config/storage/SqlTable.java), [Schema](../storage-jdbc/src/main/java/net/essentialsx/storage/jdbc/Schema.java), and [JdbcEngine](../storage-jdbc/src/main/java/net/essentialsx/storage/jdbc/JdbcEngine.java).

## 13. Building and publishing this distribution

The source tree keeps the upstream Gradle project names. With Java 25 installed, use the included Gradle wrapper from the repository root:

```text
./gradlew :EssentialsX:copyJar :EssentialsXChat:copyJar :EssentialsXProtect:copyJar :EssentialsXAntiBuild:copyJar :EssentialsXSpawn:copyJar :EssentialsXGeoIP:copyJar
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The installable shaded files are copied to `jars/` with the EssentialsXS names listed above. Do not distribute the `unshaded`, source or Javadoc JAR as the installable base plugin. The public version is fixed to `2.22.1`; internal module names need not change to generate these filenames.

For a GitHub release, publish the six installable JARs, the guide, license, checksums and corresponding source archive or the exact corresponding source commit. This distribution retains the repository's GPL license. Retain upstream copyright/license notices. Identify it clearly as a storage fork so users do not mistake its binaries or bug reports for an upstream release.

The binary bundle's documentation is also present in the source tree. Source links in this guide resolve in the repository/source checkout; those Java files are not part of the binary-only ZIP. Public packaging does not install anything into a running server or publish a GitHub release automatically.
