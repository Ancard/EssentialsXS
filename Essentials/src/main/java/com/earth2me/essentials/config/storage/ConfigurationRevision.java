package com.earth2me.essentials.config.storage;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validates detached configuration revisions before any database publication. */
public final class ConfigurationRevision {
    public static final class Draft {
        public final Map<String, String> documents;
        public final long expectedRevision;

        public Draft(Map<String, String> documents, long expectedRevision) {
            this.documents = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(documents));
            this.expectedRevision = expectedRevision;
        }
    }

    private ConfigurationRevision() {
    }

    public static void validate(Map<String, String> documents) throws Exception {
        if (!documents.containsKey("core/config.yml")) {
            throw new IllegalArgumentException("A revision requires the core configuration.");
        }
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            if (!BackendSession.configurationResource(entry.getKey())) {
                continue;
            }
            if (entry.getKey().endsWith(".yml")) {
                final CommentedConfigurationNode actual = BackendSession.decode(entry.getValue());
                if (entry.getKey().equals("core/config.yml")) {
                    if (!actual.node("backend").virtual()) {
                        throw new IllegalArgumentException(
                                "Connection settings belong to the local bootstrap.");
                    }
                    try (InputStream input =
                            ConfigurationRevision.class.getResourceAsStream("/config.yml")) {
                        if (input == null) {
                            throw new IllegalStateException(
                                    "Configuration validation template is missing.");
                        }
                        final CommentedConfigurationNode template =
                                YamlConfigurationLoader.builder()
                                        .source(
                                                () ->
                                                        new BufferedReader(
                                                                new InputStreamReader(
                                                                        input,
                                                                        StandardCharsets.UTF_8)))
                                        .build()
                                        .load();
                        template.removeChild("backend");
                        types(template, actual);
                    }
                    for (String pattern :
                            new String[] {"allowed-nicks-regex", "allowed-player-names"}) {
                        if (!actual.node(pattern).virtual()) {
                            java.util.regex.Pattern.compile(actual.node(pattern).getString());
                        }
                    }
                    final java.math.BigDecimal minimum =
                            new java.math.BigDecimal(actual.node("min-money").getString("0"));
                    final java.math.BigDecimal maximum =
                            new java.math.BigDecimal(
                                    actual.node("max-money").getString("10000000000000"));
                    if (minimum.compareTo(maximum) > 0) {
                        throw new IllegalArgumentException("Minimum money exceeds maximum money.");
                    }
                }
            }
        }
    }

    private static void types(ConfigurationNode template, ConfigurationNode actual) {
        if (actual.virtual() || actual.raw() == null || template.raw() == null) {
            return;
        }
        if (template.isMap()) {
            if (!actual.isMap()) {
                throw new IllegalArgumentException(
                        "Configuration section has an invalid type: " + actual.path());
            }
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    template.childrenMap().entrySet()) {
                types(entry.getValue(), actual.node(entry.getKey()));
            }
        } else if (template.isList()) {
            if (!actual.isList()) {
                throw new IllegalArgumentException(
                        "Configuration list has an invalid type: " + actual.path());
            }
        } else if (template.raw() instanceof Boolean && !(actual.raw() instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "Configuration boolean has an invalid type: " + actual.path());
        } else if (template.raw() instanceof Number) {
            try {
                new java.math.BigDecimal(actual.getString());
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException(
                        "Configuration number has an invalid type: " + actual.path());
            }
        }
    }

    public static File staging(BackendSession session) {
        return new File(session.catalog().core(), "backend-staging/core");
    }

    public static Map<String, String> loadStage(BackendSession session) throws Exception {
        final Map<String, String> revision = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry :
                new YamlEngine(new ResourceCatalog(staging(session))).loadAll().entrySet()) {
            if (BackendSession.configurationResource(entry.getKey())) {
                revision.put(entry.getKey(), entry.getValue());
            }
        }
        if (revision.containsKey("core/config.yml")
                && !BackendSession.decode(revision.get("core/config.yml"))
                        .node("backend")
                        .virtual()) {
            throw new IllegalArgumentException(
                    "Connection settings belong to the local bootstrap.");
        }
        final Map<String, String> canonical = BackendMigration.canonical(revision);
        validate(canonical);
        return canonical;
    }

    public static long expectedRevision(BackendSession session) throws Exception {
        return Long.parseLong(
                new String(
                                java.nio.file.Files.readAllBytes(
                                        session.catalog()
                                                .local("backend-staging/expected-revision.txt")
                                                .toPath()),
                                StandardCharsets.UTF_8)
                        .trim());
    }
}
