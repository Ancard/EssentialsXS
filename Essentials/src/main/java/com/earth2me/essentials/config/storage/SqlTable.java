package com.earth2me.essentials.config.storage;

/** Lists the SQL resources approved for this fork; external messaging modules are excluded. */
public enum SqlTable {
    USERS("users"),
    USER_STATE("user_state"),
    USER_BALANCES("user_balances"),
    USER_HOMES("user_homes"),
    USER_MAIL("user_mail"),
    USER_IGNORES("user_ignores"),
    USER_COOLDOWNS("user_cooldowns"),
    USER_NAMES("user_names"),
    USER_EXTRAS("user_extras"),
    WARPS("warps"),
    JAILS("jails"),
    SPAWNS("spawns"),
    RESOURCE_DOCUMENTS("resource_documents"),
    CONFIG_CORE("config_core"),
    CONFIG_CHAT("config_chat"),
    CONFIG_PROTECT("config_protect"),
    CONFIG_ANTIBUILD("config_antibuild"),
    CONFIG_SPAWN("config_spawn"),
    CONFIG_GEOIP("config_geoip"),
    DATA_SETS("data_sets"),
    CONFIG_PROFILES("config_profiles"),
    CONFIG_REVISIONS("config_revisions"),
    SCHEMA_HISTORY("schema_history"),
    BACKEND_LEASES("backend_leases"),
    IMPORT_RUNS("import_runs");

    private final String suffix;

    SqlTable(final String suffix) {
        this.suffix = suffix;
    }

    public String nameWithPrefix(final String prefix) {
        if (prefix == null || !prefix.matches("[a-z][a-z0-9_]{0,23}")) {
            throw new IllegalArgumentException(
                    "The SQL table prefix must contain 1-24 lowercase ASCII letters, digits or"
                            + " underscores and start with a letter.");
        }
        return prefix + suffix;
    }
}
