package com.earth2me.essentials.config.storage;

import net.essentialsx.storage.StorageEngine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provides asynchronous repositories with the traditional on-disk layout. */
public final class YamlEngine implements StorageEngine {
    private final ResourceCatalog catalog;

    public YamlEngine(ResourceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Map<String, String> loadAll() throws Exception {
        final Map<String, String> result = new LinkedHashMap<>();
        scan(catalog.core(), result);
        final File geo = catalog.file("geoip/config.yml");
        if (geo.isFile()) {
            result.put(
                    "geoip/config.yml",
                    new String(Files.readAllBytes(geo.toPath()), StandardCharsets.UTF_8));
        }
        final org.spongepowered.configurate.CommentedConfigurationNode names =
                org.spongepowered.configurate.CommentedConfigurationNode.root();
        final File index = new File(catalog.core(), "usermap.bin");
        if (index.isFile()) {
            try (java.io.DataInputStream input =
                    new java.io.DataInputStream(
                            new java.io.BufferedInputStream(new java.io.FileInputStream(index)))) {
                while (input.available() > 0) {
                    names.node(input.readUTF())
                            .set(new UUID(input.readLong(), input.readLong()).toString());
                }
            }
        } else {
            for (Map.Entry<String, String> entry : result.entrySet()) {
                if (entry.getKey().startsWith("core/userdata/")) {
                    final String name =
                            BackendSession.decode(entry.getValue())
                                    .node("last-account-name")
                                    .getString();
                    if (name != null) {
                        if (!names.node(name).virtual()) {
                            throw new IllegalStateException(
                                    "Ambiguous historical names require a valid YAML usermap before"
                                            + " migration.");
                        }
                        names.node(name)
                                .set(entry.getKey().substring(14, entry.getKey().length() - 4));
                    }
                }
            }
        }
        result.put("core/@names", BackendSession.encode(names));
        return result;
    }

    private void scan(File directory, Map<String, String> result) throws Exception {
        final File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath())) {
                throw new IllegalStateException(
                        "Storage migration does not follow symbolic links.");
            }
            if (file.isDirectory()
                    && Arrays.asList("userdata", "warps", "kits", "messages")
                            .contains(file.getName())) {
                scan(file, result);
            } else if (file.isFile()) {
                final String key = catalog.key(file);
                if (key != null) {
                    result.put(
                            key,
                            new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                }
            }
        }
    }

    @Override
    public void commit(Map<String, String> values, Set<String> deleted) throws Exception {
        Files.createDirectories(catalog.core().toPath());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().equals("core/@names")) {
                final Map<String, UUID> names = new LinkedHashMap<>();
                for (Map.Entry<Object, org.spongepowered.configurate.CommentedConfigurationNode>
                        name : BackendSession.decode(entry.getValue()).childrenMap().entrySet()) {
                    names.put(
                            name.getKey().toString(), UUID.fromString(name.getValue().getString()));
                }
                com.earth2me.essentials.userstorage.ModernUUIDCache.writeNameUuidMap(
                        new File(catalog.core(), "usermap.bin"), names);
                continue;
            }
            final File file = catalog.file(entry.getKey());
            Files.createDirectories(file.getParentFile().toPath());
            final Path temporary =
                    Files.createTempFile(file.getParentFile().toPath(), "essentials-", ".tmp");
            try {
                Files.write(temporary, entry.getValue().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(
                            temporary,
                            file.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        for (String key : deleted) {
            if (!YamlRepositories.delete(catalog.file(key))) {
                throw new java.io.IOException("Cannot delete the YAML repository resource.");
            }
        }
        final java.util.Set<UUID> identities = new java.util.LinkedHashSet<>();
        final File[] profiles = new File(catalog.core(), "userdata").listFiles();
        if (profiles != null) {
            for (File profile : profiles) {
                if (profile.getName().endsWith(".yml")) {
                    identities.add(
                            UUID.fromString(
                                    profile.getName()
                                            .substring(0, profile.getName().length() - 4)));
                }
            }
        }
        com.earth2me.essentials.userstorage.ModernUUIDCache.writeUuidCache(
                new File(catalog.core(), "uuids.bin"), identities);
    }

    @Override
    public void heartbeat() {
    }

    @Override
    public boolean initialized() {
        return true;
    }

    @Override
    public int beginImport(String hash) {
        return 0;
    }

    @Override
    public void advanceImport(int progress, boolean completed) {
    }

    @Override
    public void markInitialized() {
    }

    @Override
    public String description() {
        return "yaml";
    }

    @Override
    public void close() {
    }
}
