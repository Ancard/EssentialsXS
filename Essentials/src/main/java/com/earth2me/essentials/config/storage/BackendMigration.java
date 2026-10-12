package com.earth2me.essentials.config.storage;

import net.essentialsx.storage.StorageEngine;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Imports immutable source manifests and activates a target only after semantic verification. */
public final class BackendMigration {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile String lastStatus = "IDLE";

    private BackendMigration() {
    }

    public static boolean busy() {
        return BUSY.get();
    }

    public static String status() {
        return lastStatus;
    }

    public static CompletableFuture<String> execute(File root, String operation, boolean dryRun) {
        if (!BUSY.compareAndSet(false, true)) {
            throw new IllegalStateException("A storage migration is already running.");
        }
        lastStatus = operation.toUpperCase(java.util.Locale.ENGLISH) + ":VALIDATING";
        final ExecutorService worker =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "EssentialsBackendMigration"));
        final CompletableFuture<String> result = new CompletableFuture<>();
        worker.execute(
                () -> {
                    try {
                        final ResourceCatalog catalog = new ResourceCatalog(root);
                        final CommentedConfigurationNode bootstrap =
                                YamlConfigurationLoader.builder()
                                        .file(new File(root, "config.yml"))
                                        .build()
                                        .load();
                        if (operation.equals("import")) {
                            final Map<String, String> source =
                                    canonical(new YamlEngine(catalog).loadAll());
                            ConfigurationRevision.validate(source);
                            if (source.containsKey("core/@names")) {
                                for (org.spongepowered.configurate.CommentedConfigurationNode
                                        identity :
                                                BackendSession.decode(source.get("core/@names"))
                                                        .childrenMap()
                                                        .values()) {
                                    if (!source.containsKey(
                                            "core/userdata/" + identity.getString() + ".yml")) {
                                        throw new IllegalStateException(
                                                "The YAML name index references a missing profile;"
                                                        + " repair it before importing.");
                                    }
                                }
                            }
                            final String hash = manifest(source);
                            if (dryRun) {
                                lastStatus =
                                        "VALIDATED; documents="
                                                + source.size()
                                                + "; manifest="
                                                + hash;
                                result.complete(
                                        "Validated "
                                                + source.size()
                                                + " documents; manifest="
                                                + hash);
                                return;
                            }
                            final File backup =
                                    new File(
                                            root,
                                            "backend-backups/"
                                                    + System.currentTimeMillis()
                                                    + "/core");
                            new YamlEngine(new ResourceCatalog(backup))
                                    .commit(source, Collections.emptySet());
                            lastStatus = "IMPORT:CONNECTING_AND_MIGRATING_SCHEMA";
                            try (StorageEngine target = BackendSession.openSql(bootstrap)) {
                                target.loadAll();
                                final int progress = target.beginImport(hash);
                                final List<String> keys = new ArrayList<>(source.keySet());
                                Collections.sort(keys);
                                for (int start = progress; start < keys.size(); start += 50) {
                                    final Map<String, String> batch = new LinkedHashMap<>();
                                    for (int i = start;
                                            i < Math.min(start + 50, keys.size());
                                            i++) {
                                        batch.put(keys.get(i), source.get(keys.get(i)));
                                    }
                                    target.commit(batch, Collections.emptySet());
                                    target.advanceImport(Math.min(start + 50, keys.size()), false);
                                    lastStatus =
                                            "IMPORT:"
                                                    + Math.min(start + 50, keys.size())
                                                    + "/"
                                                    + keys.size();
                                }
                                if (source.containsKey("core/@names")) {
                                    target.commit(
                                            Collections.singletonMap(
                                                    "core/@names", source.get("core/@names")),
                                            Collections.emptySet());
                                }
                                final Map<String, String> roundTrip = canonical(target.loadAll());
                                verify(source, roundTrip);
                                if (!hash.equals(
                                        manifest(canonical(new YamlEngine(catalog).loadAll())))) {
                                    throw new IllegalStateException(
                                            "The source changed during migration; activation was"
                                                    + " cancelled.");
                                }
                                target.advanceImport(keys.size(), true);
                            }
                            result.complete(
                                    "Import verified. Select backend.type: mysql and perform a"
                                            + " controlled restart.");
                            lastStatus = "IMPORT:COMPLETE; restart required";
                        } else if (operation.equals("export")) {
                            final BackendSession session = BackendSession.current();
                            if (session == null) {
                                throw new IllegalStateException(
                                        "Export requires an active SQL backend.");
                            }
                            session.flush().get();
                            final Map<String, String> source = session.snapshot();
                            final File destination =
                                    new File(
                                            root,
                                            "backend-exports/"
                                                    + System.currentTimeMillis()
                                                    + "/core");
                            final YamlEngine yaml =
                                    new YamlEngine(new ResourceCatalog(destination));
                            yaml.commit(source, Collections.emptySet());
                            verify(canonical(source), canonical(yaml.loadAll()));
                            result.complete(
                                    "Export verified: "
                                            + destination.getCanonicalPath()
                                            + ". Keep the local bootstrap when switching back to"
                                            + " YAML.");
                            lastStatus = "EXPORT:COMPLETE; destination=" + destination;
                        } else {
                            throw new IllegalArgumentException("Unsupported migration operation.");
                        }
                    } catch (Exception failure) {
                        lastStatus = "FAILED:" + failure.getClass().getSimpleName();
                        result.completeExceptionally(failure);
                    } finally {
                        try {
                            final ResourceCatalog safe =
                                    new ResourceCatalog(new File(root, "backend-operations/core"));
                            java.nio.file.Files.createDirectories(safe.core().toPath());
                            java.nio.file.Files.write(
                                    new File(safe.core(), "last-operation.txt").toPath(),
                                    lastStatus.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception reportFailure) {
                            lastStatus += "; report unavailable";
                        }
                        BUSY.set(false);
                        worker.shutdown();
                    }
                });
        return result;
    }

    public static Map<String, String> canonical(Map<String, String> source) throws Exception {
        final Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : source.entrySet()) {
            String value = e.getValue();
            if (e.getKey().endsWith(".yml") || e.getKey().equals("core/@names")) {
                final CommentedConfigurationNode node = BackendSession.decode(value);
                if (e.getKey().equals("core/config.yml")) {
                    node.removeChild("backend");
                }
                if (e.getKey().startsWith("core/userdata/") && !node.node("money").virtual()) {
                    node.node("money")
                            .set(
                                    new java.math.BigDecimal(node.node("money").getString())
                                            .toString());
                }
                value = BackendSession.encode(node);
            }
            result.put(e.getKey(), value);
        }
        return result;
    }

    public static void verify(Map<String, String> source, Map<String, String> target)
            throws Exception {
        if (!source.keySet().equals(target.keySet())) {
            throw new IllegalStateException("The migration resource manifests differ.");
        }
        for (String key : source.keySet()) {
            if (key.endsWith(".yml") || key.equals("core/@names")) {
                if (!Objects.equals(
                        BackendSession.decode(source.get(key)).raw(),
                        BackendSession.decode(target.get(key)).raw())) {
                    throw new IllegalStateException(
                            "A migrated configuration or player document differs.");
                }
            } else if (!source.get(key).equals(target.get(key))) {
                throw new IllegalStateException("A migrated text resource differs.");
            }
        }
    }

    private static String manifest(Map<String, String> source) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final List<String> keys = new ArrayList<>(source.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(source.get(key).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        final StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) {
            result.append(String.format("%02x", b & 255));
        }
        return result.toString();
    }
}
