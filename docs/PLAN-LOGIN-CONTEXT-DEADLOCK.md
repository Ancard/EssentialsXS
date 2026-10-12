# EssentialsXS login context deadlock correction plan

## Problem and evidence

### Responsibility and scope

This is a regression introduced by the EssentialsXS storage implementation. The repository diff confirms that the SQL-specific main-thread dispatch and synchronous wait in `ModernUserMap` were added by this fork; the existing Essentials LuckPerms context handler was not changed. The observed third-party permission queries expose the regression. The correction belongs entirely in EssentialsXS.

No changes to LuckPerms, AuthMe, NotQuests, their JARs, or their configuration are required for the proposed correction. `LuckPermsHandler.java` is Essentials' own integration adapter inside the EssentialsXS source tree, not LuckPerms source code. Any changes to that adapter are changes to our plugin.

Compatibility acceptance must retain the same third-party plugin versions and configurations used to reproduce the failure. Disabling their integrations or increasing watchdog/publication timeouts is not an acceptable correction.

The development test on October 12, 2026 loaded 41 plugins successfully. EssentialsXS reached SQL READY and Vault registered its economy provider. A real player login then stalled the server and triggered watchdog shutdown.

The async command builder queried LuckPerms permissions while processing NotQuests command visibility. LuckPerms invoked EssentialsXS `CombinedCalculator.calculate`, which called `ModernUserMap.loadUncachedUser(UUID)`. In SQL mode this method dispatches to the main thread and waits up to five seconds, even before checking the user cache. Meanwhile, the main thread queried LuckPerms permissions through AuthMe and subsequently through Essentials join processing, waiting for the same LuckPerms context cache. Repeated publication timeouts prolonged the stall until shutdown.

The correction must remove this circular dependency while preserving context permissions and the rule that Bukkit object creation and mutation happen on the main thread. Raising timeouts or evaluating Bukkit-dependent profile loading on an async thread would not satisfy this requirement.

## 1. Preserve evidence and define the regression

- Preserve the startup, first login, context exceptions, watchdog stacks, and shutdown evidence under the development workspace before another startup replaces `latest.log`.
- Record the exact installed plugin versions, Java/Paper versions, active backend, and release JAR hashes. Redact secrets and unnecessary player identifiers from public evidence.
- Inspect repository instructions and the official documentation for the pinned LuckPerms and Paper APIs before implementing lifecycle notifications.
- Add a deterministic regression fixture that models an async permission calculation holding a context lock while the main thread checks permissions. Use latches and bounded completion assertions rather than arbitrary sleeps.

Acceptance: the fixture identifies the existing circular dependency without hanging the test runner.

## 2. Make permission context calculation independent of profile loading

- Introduce an internal UUID-keyed context snapshot store. Each published entry contains immutable context values and the profile/session generation, without retaining Bukkit objects.
- Evaluate the registered Essentials context functions on the main thread when a valid user is published or its context state changes. Preserve the existing registration contract for additional calculators; execute their callbacks during snapshot preparation, not during LuckPerms permission calculation.
- Change `CombinedCalculator.calculate` to read an already published snapshot and forward its values only. It must not load a user, query SQL/YAML, schedule a task and wait, or invoke mutable user getters.
- If no valid snapshot exists, emit no Essentials context values. Do not invent `false` states or create a profile from the permission callback.
- Publish calculator registrations safely so async readers cannot observe a collection being modified concurrently.

Acceptance: permission calculation completes without main-thread progress, database availability, or user-cache population; existing context keys and values are preserved.

## 3. Publish and refresh context state at the correct lifecycle boundaries

- Trace the first permission query during login and publish available context state before it, using preloaded repository data and supported player lifecycle hooks. New-player handling must also produce a valid initial state without synchronous JDBC.
- Audit AFK, mute and mute expiration, vanish, jail and jail name changes, reload, and externally registered contexts. Refresh snapshots after successful state changes and notify LuckPerms after publication, outside permission calculation and conflicting locks.
- Keep online snapshots independent of the evictable user cache. Clear or replace them on logout, deletion, backend shutdown, and reload as appropriate.
- Reject queued publications from a previous login or deleted profile using generation checks. A reconnect must never inherit a stale player session snapshot.
- Ensure notifications do not recursively trigger profile loading or context preparation. Specify handling of failed backend availability explicitly.

Acceptance: context-dependent permissions update correctly for existing and first-time players, reconnects, timed state expiry, and cache eviction.

## 4. Harden user lookup boundaries

- Audit `ModernUserMap` and the relevant async callers for blocking main-thread dispatch, including cache loaders, economy consumers, and user listing operations.
- Provide an explicit internal lookup for already available data, with a documented nonblocking contract. Route permission callbacks through the snapshot store rather than public loading methods.
- Check valid existing caches before considering any dispatch in applicable lookup paths, with safe publication and visibility guarantees. Treat this as an additional safeguard, not the sole correction.
- Keep profile construction and Bukkit publication on the main thread, and JDBC on workers. Separate preparation from publication where loading is necessary.
- Ensure expired or failed publication tasks cannot later attach obsolete player objects or overwrite a newer generation.
- Preserve public Essentials and Vault signatures, YAML behavior, and explicit backend failure reporting.

Acceptance: the corrected login path has no synchronous dependency from a LuckPerms context callback to the game thread, and no JDBC executes on that thread.

## 5. Validate the correction

- Run meaningful unit/concurrency tests for the regression, missing snapshots, all built-in contexts, registration changes, cache eviction, logout/reconnect generations, and database failure during permission reads.
- Run the existing storage and economy regression suites and required style/build checks through the workspace scripts.
- Deploy the corrected EssentialsXS build using the controlled development deployment. Use only Paper at `127.0.0.1:25566` and the isolated development SQL instance at `127.0.0.1:3307`.
- Repeat real player login with the same 41 plugins, including AuthMe, LuckPerms, NotQuests and Vault. Verify authentication, command suggestions, chat prefix/suffix, balance, homes, mail, spawn, and context-dependent permissions using a dedicated temporary permission group where necessary.
- Repeat logout/login and a controlled restart. Observe permission reads during user-cache eviction and verify that RCON remains responsive during player login.
- Validate YAML separately with isolated fixtures and controlled configuration restoration; do not silently fall back from SQL.
- Record timings and compare startup-only success with actual player interaction evidence. RCON and mock tests cannot replace real login verification.

Acceptance: real login completes, the server remains responsive, no publication timeout or watchdog stall occurs, and context changes continue to affect permissions correctly.

## 6. Document and package the result

- Update the storage guide and progress report with the cause, correction, precise test evidence, remaining limitations, and the tested compatibility matrix.
- Produce updated EssentialsXS 2.22.1 JARs and release checksums once the correction passes. Document that the replaced build includes a functional compatibility fix.
- Track GeoIP's missing license/database, AuthMe's file-based spawn integration, obsolete login/spawn listeners, and third-party startup warnings separately. They must not be reported as resolved by the context fix.

## Completion criteria

The work is complete when permission callbacks consume immutable published data without waiting for profile publication; the original concurrent login regression passes; SQL and YAML checks pass; and real player login with the full installed plugin set no longer stalls Paper. All new code comments remain in English, and all tests, evidence, servers, and files remain within the isolated development workspace.
