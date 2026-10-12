package com.earth2me.essentials.config.storage;

import org.spongepowered.configurate.ConfigurationNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Validates local connection settings and resolves credentials without logging them. */
public final class BackendOptions {
    private BackendOptions() {
    }

    public static Map<String, String> read(ConfigurationNode root) {
        final ConfigurationNode b = root.node("backend"), m = b.node("mysql");
        if (!m.node("credentials", "username").virtual()
                || !m.node("credentials", "password").virtual()) {
            throw new IllegalArgumentException(
                    "Literal backend credentials are forbidden; use environment references.");
        }
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("engine", text(m.node("engine"), "mysql"));
        values.put("host", text(m.node("host"), "127.0.0.1"));
        values.put("port", text(m.node("port"), "3306"));
        values.put("database", text(m.node("database"), "essentialsxs"));
        values.put("prefix", text(m.node("table-prefix"), "essxs_"));
        SqlTable.USERS.nameWithPrefix(values.get("prefix"));
        values.put("dataset", text(b.node("data-set"), "default"));
        values.put("profile", text(b.node("configuration-profile"), "default"));
        values.put("instance", text(b.node("instance-id"), "essentialsxs-local"));
        values.put("pool", text(m.node("pool", "maximum-size"), "4"));
        values.put("timeout", text(m.node("pool", "connection-timeout-ms"), "10000"));
        values.put("preloadBytes", text(m.node("persistence", "preload-max-bytes"), "268435456"));
        final String tls = text(m.node("tls", "mode"), "disabled");
        final boolean maria = values.get("engine").equals("mariadb");
        if (!java.util.Arrays.asList("disabled", "verify-identity").contains(tls)) {
            throw new IllegalArgumentException("Unsupported backend TLS mode.");
        }
        values.put(
                "tls",
                tls.equals("disabled")
                        ? (maria ? "disable" : "DISABLED")
                        : (maria ? "verify-full" : "VERIFY_IDENTITY"));
        values.put(
                "username", secret(m.node("credentials", "username-env"), "ESSENTIALSXS_DB_USER"));
        values.put(
                "password",
                secret(m.node("credentials", "password-env"), "ESSENTIALSXS_DB_PASSWORD"));
        final int pool = Integer.parseInt(values.get("pool"));
        final long timeout = Long.parseLong(values.get("timeout"));
        if (pool < 1 || pool > 32 || timeout < 1000 || timeout > 120000) {
            throw new IllegalArgumentException("Invalid backend pool or connection timeout.");
        }
        return values;
    }

    private static String secret(ConfigurationNode node, String fallback) {
        final String name = text(node, fallback);
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid credential environment variable.");
        }
        final String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    "A required backend credential is missing from the environment.");
        }
        return value;
    }

    private static String text(ConfigurationNode node, String fallback) {
        return node.getString(fallback);
    }
}
