# EssentialsXS 2.22.1 — Release Status

The public distribution uses EssentialsXS artifact names and version 2.22.1. Internal Bukkit identifiers and existing public API packages remain compatible with EssentialsX.

The current user-facing documentation is the [Storage Guide](ESSENTIALSXS-STORAGE-GUIDE.md). It contains backend defaults, commands, permissions, schema ownership, migration tutorials, lifecycle behavior and implementation conflict resolutions.

## Existing implementation evidence

- 56 core tests passed.
- Six integration cases passed on MySQL 8.4.11; the same six passed on MariaDB 11.8.6.
- Six included modules enabled on Paper 26.3 / Java 25 with YAML and SQL.
- A local SQL import and verified YAML export completed.
- Configuration stage, validation, publication and reload completed.
- Vault registered EssentialsX Economy as the active provider.
- A player exercised homes, mail, kits, balance and spawn. Home/mail persistence was confirmed after reconnect and a full restart.

These results concern the storage implementation before the public packaging/default-label update. The public build must be distinguished from that earlier runtime validation: changing labels and default connection values does not provide new runtime evidence.

## Remaining acceptance work

### Critical regression discovered after the public build

On October 12, 2026 the public EssentialsXS 2.22.1 build was deployed to the isolated development Paper 26.3 / Java 25 server with 41 plugins. All plugins initially enabled, SQL reached READY, and Vault, LuckPerms, Jobs, ShopkeepersAddon and AuthMe registered their integrations. A real player login then stalled the server and triggered watchdog shutdown after 60 seconds.

The fork added synchronous main-thread publication to `ModernUserMap.loadUncachedUser`. LuckPerms context calculation invoked this method from an async command-building thread while the game thread was waiting for a permission calculation. Repeated `Profile publication failed` and `TimeoutException` errors confirm the regression. The correction belongs entirely in EssentialsXS; third-party plugin modifications are not required. See the [correction plan](PLAN-LOGIN-CONTEXT-DEADLOCK.md).

This source revision is a development checkpoint and must not be described as production-ready. GeoIP's missing licensed database and AuthMe's file-based spawn integration remain separate acceptance findings.

Extended outage/load acceptance and complete moderation testing with real players remain follow-up work. GeoIP requires a valid local licensed database. The SQL service supports one writer per data set, manual configuration refresh and deferred saves with explicit flush; simultaneous server synchronization is outside this version.

Public artifacts and their checksums are recorded separately when the release package is built. Private development paths, account names and environment-specific connection details are not part of the public guide or distribution.
