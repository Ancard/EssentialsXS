package com.earth2me.essentials.config.storage;

import net.essentialsx.storage.ConfigurationRepository;
import net.essentialsx.storage.SharedStateRepository;
import net.essentialsx.storage.UserRepository;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Provides the repository contracts over YAML with ordered writes and truthful flush barriers. */
public final class YamlRepositories implements AutoCloseable {
    private static final java.util.concurrent.ConcurrentMap<File, Long> GENERATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static long generation(File resource) {
        return GENERATIONS.getOrDefault(resource.getAbsoluteFile(), 0L);
    }

    private final YamlEngine engine;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "EssentialsYamlRepository"));
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private volatile boolean closed;

    public YamlRepositories(ResourceCatalog catalog) {
        engine = new YamlEngine(catalog);
    }

    public UserRepository users() {
        return new View("core/userdata/");
    }

    public ConfigurationRepository configuration() {
        return new View("");
    }

    public SharedStateRepository sharedState() {
        return new View("core/");
    }

    private <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> operation) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(
                    new IllegalStateException("The YAML repository is closed."));
            return result;
        }
        worker.execute(
                () -> {
                    try {
                        result.complete(operation.call());
                    } catch (Exception failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    private void persist() throws Exception {
        if (pending.isEmpty() && removed.isEmpty()) {
            return;
        }
        engine.commit(new LinkedHashMap<>(pending), new LinkedHashSet<>(removed));
        pending.clear();
        removed.clear();
    }

    private final class View
            implements UserRepository, ConfigurationRepository, SharedStateRepository {
        private final String prefix;

        private View(String prefix) {
            this.prefix = prefix;
        }

        public CompletableFuture<Optional<String>> read(String key) {
            return submit(() -> Optional.ofNullable(engine.loadAll().get(prefix + key)));
        }

        public CompletableFuture<Map<String, String>> enumerate() {
            return submit(
                    () -> {
                        final Map<String, String> result = new LinkedHashMap<>();
                        for (Map.Entry<String, String> entry : engine.loadAll().entrySet()) {
                            if (entry.getKey().startsWith(prefix)) {
                                result.put(
                                        entry.getKey().substring(prefix.length()),
                                        entry.getValue());
                            }
                        }
                        return Collections.unmodifiableMap(result);
                    });
        }

        public CompletableFuture<Void> write(String key, String snapshot) {
            return submit(
                    () -> {
                        pending.put(prefix + key, snapshot);
                        removed.remove(prefix + key);
                        persist();
                        return null;
                    });
        }

        public CompletableFuture<Void> delete(String key) {
            return submit(
                    () -> {
                        pending.remove(prefix + key);
                        removed.add(prefix + key);
                        persist();
                        return null;
                    });
        }

        public CompletableFuture<Void> flush() {
            return submit(
                    () -> {
                        persist();
                        return null;
                    });
        }
    }

    public static boolean exists(File resource) {
        return resource.exists();
    }

    public static boolean delete(File resource) {
        final boolean deleted = !resource.exists() || resource.delete();
        if (deleted) {
            GENERATIONS.merge(resource.getAbsoluteFile(), 1L, Long::sum);
        }
        return deleted;
    }

    public static File[] enumerate(File directory) {
        return directory.listFiles();
    }

    public void close() throws Exception {
        final CompletableFuture<Void> barrier = configuration().flush();
        closed = true;
        worker.shutdown();
        barrier.get(30, TimeUnit.SECONDS);
        engine.close();
    }
}
