package com.earth2me.essentials.config.storage;

import net.essentialsx.storage.ConfigurationRepository;
import net.essentialsx.storage.DocumentRepository;
import net.essentialsx.storage.SharedStateRepository;
import net.essentialsx.storage.StorageEngine;
import net.essentialsx.storage.UserRepository;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Keeps synchronous consumers in memory and serializes all SQL I/O on one worker. */
public final class BackendSession implements AutoCloseable {
    private static volatile BackendSession current;
    private static final Object YAML_MUTATION_LOCK = new Object();

    public static Object mutationLock() {
        final BackendSession session = current;
        return session == null ? YAML_MUTATION_LOCK : session;
    }

    private final ResourceCatalog catalog;
    private final CommentedConfigurationNode bootstrap;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "EssentialsBackend"));
    private final Map<String, String> cache = new LinkedHashMap<>();
    private final Map<String, String> dirty = new LinkedHashMap<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    private final Map<String, Long> sequences = new HashMap<>();
    private final Map<String, Long> generations = new HashMap<>();
    private final Map<String, Long> modified = new HashMap<>();
    private long sequence;
    private final ThreadLocal<Map<String, String>> atomic = new ThreadLocal<>();
    private final Logger logger;
    private StorageEngine engine;
    private volatile boolean degraded;
    private volatile boolean closed;
    private volatile boolean closingCapture;
    private volatile boolean configurationBusy;
    private final boolean sqlConfiguration;
    private long lastHeartbeat;
    private final long shutdownSeconds;
    private final long flushMillis;

    private BackendSession(File root, CommentedConfigurationNode bootstrap, Logger logger)
            throws Exception {
        catalog = new ResourceCatalog(root);
        this.bootstrap = bootstrap.copy();
        this.logger = logger;
        final String source =
                bootstrap.node("backend", "configuration", "source").getString("backend");
        if (!source.equals("backend") && !source.equals("yaml")) {
            throw new IllegalArgumentException("Invalid configuration source.");
        }
        if (!bootstrap
                .node("backend", "configuration", "refresh")
                .getString("manual")
                .equals("manual")) {
            throw new IllegalArgumentException("Only manual configuration refresh is supported.");
        }
        sqlConfiguration = source.equals("backend");
        shutdownSeconds =
                bootstrap
                        .node("backend", "mysql", "persistence", "shutdown-timeout-seconds")
                        .getLong(30);
        flushMillis =
                bootstrap
                        .node("backend", "mysql", "persistence", "flush-interval-ms")
                        .getLong(1000);
        if (shutdownSeconds < 1
                || shutdownSeconds > 300
                || flushMillis < 1
                || flushMillis > 60000) {
            throw new IllegalArgumentException("Invalid persistence timeouts.");
        }
    }

    BackendSession(
            File root, CommentedConfigurationNode bootstrap, Logger logger, StorageEngine engine)
            throws Exception {
        this(root, bootstrap, logger);
        this.engine = engine;
        cache.putAll(engine.loadAll());
    }

    public static BackendSession current() {
        return current;
    }

    public static boolean active() {
        return current != null && !current.closed;
    }

    public static void start(File root, Logger logger) throws Exception {
        final CommentedConfigurationNode bootstrap =
                YamlConfigurationLoader.builder().file(new File(root, "config.yml")).build().load();
        final String type = bootstrap.node("backend", "type").getString("yaml");
        if (type.equals("yaml")) {
            return;
        }
        if (!type.equals("mysql")) {
            throw new IllegalArgumentException("backend.type must be yaml or mysql.");
        }
        final BackendSession session = new BackendSession(root, bootstrap, logger);
        final Future<?> initialization =
                session.worker.submit(
                        () -> {
                            try {
                                session.engine = openSql(bootstrap);
                                if (!session.engine.initialized()) {
                                    final Map<String, String> local =
                                            new YamlEngine(session.catalog).loadAll();
                                    local.remove("core/config.yml");
                                    local.remove("core/@names");
                                    if (!local.isEmpty()) {
                                        throw new IllegalStateException(
                                                "Local data must be imported before selecting the"
                                                        + " SQL backend.");
                                    }
                                }
                                final Map<String, String> loaded = session.engine.loadAll();
                                if (loaded.containsKey("core/config.yml")
                                        && session.sqlConfiguration) {
                                    ConfigurationRevision.validate(loaded);
                                }
                                long bytes = 0;
                                for (String text : loaded.values()) {
                                    bytes += text.getBytes(StandardCharsets.UTF_8).length;
                                }
                                final long limit =
                                        bootstrap
                                                .node(
                                                        "backend",
                                                        "mysql",
                                                        "persistence",
                                                        "preload-max-bytes")
                                                .getLong(268435456L);
                                if (bytes > limit
                                        || Runtime.getRuntime().totalMemory()
                                                        - Runtime.getRuntime().freeMemory()
                                                > Runtime.getRuntime().maxMemory() * 0.75) {
                                    throw new IllegalStateException(
                                            "Storage preload exceeds the configured memory"
                                                    + " budget.");
                                }
                                session.cache.putAll(loaded);
                                if (!session.cache.containsKey("core/config.yml")) {
                                    final CommentedConfigurationNode initial = bootstrap.copy();
                                    initial.removeChild("backend");
                                    session.cache.put("core/config.yml", encode(initial));
                                    session.engine.commit(
                                            Collections.singletonMap(
                                                    "core/config.yml", encode(initial)),
                                            Collections.emptySet());
                                }
                                session.engine.markInitialized();
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        });
        try {
            initialization.get(
                    bootstrap
                            .node("backend", "mysql", "persistence", "startup-timeout-seconds")
                            .getLong(120),
                    TimeUnit.SECONDS);
        } catch (Exception failure) {
            initialization.cancel(true);
            session.worker.submit(
                    () -> {
                        if (session.engine != null) {
                            try {
                                session.engine.close();
                            } catch (Exception ignored) {
                            }
                        }
                    });
            session.worker.shutdown();
            throw failure;
        }
        current = session;
        session.worker.scheduleWithFixedDelay(
                session::cycle, session.flushMillis, session.flushMillis, TimeUnit.MILLISECONDS);
    }

    public static StorageEngine openSql(CommentedConfigurationNode bootstrap) throws Exception {
        if (Integer.parseInt(System.getProperty("java.specification.version").replace("1.", ""))
                < 11) {
            throw new IllegalStateException("SQL storage requires Java 11 or newer.");
        }
        return (StorageEngine)
                Class.forName("net.essentialsx.storage.jdbc.JdbcEngine")
                        .getConstructor(Map.class)
                        .newInstance(BackendOptions.read(bootstrap));
    }

    public static String encode(CommentedConfigurationNode node) throws Exception {
        final StringWriter writer = new StringWriter();
        YamlConfigurationLoader.builder().sink(() -> new BufferedWriter(writer)).build().save(node);
        return writer.toString();
    }

    public static CommentedConfigurationNode decode(String text) throws Exception {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(text)))
                .build()
                .load();
    }

    public synchronized String read(String key) {
        return cache.get(key);
    }

    public synchronized long generation(String key) {
        return generations.getOrDefault(key, 0L);
    }

    public synchronized long modified(String key) {
        return modified.getOrDefault(key, 0L);
    }

    public synchronized void write(String key, String value, long generation) {
        if (generation != generation(key)) {
            throw new IllegalStateException(
                    "A deleted resource cannot be saved by an older snapshot.");
        }
        write(key, value);
    }

    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(cache);
    }

    public ResourceCatalog catalog() {
        return catalog;
    }

    public CommentedConfigurationNode bootstrap() {
        return bootstrap.copy();
    }

    public synchronized void requireWritable() {
        if (closed || ((degraded || closingCapture) && atomic.get() == null)) {
            throw new IllegalStateException(
                    "Persistent storage is unavailable; the mutation was rejected.");
        }
        if (dirty.size() + deleted.size() > 10000) {
            throw new IllegalStateException("Storage queue capacity exceeded.");
        }
    }

    public synchronized void requireWritable(String key, long generation) {
        requireWritable();
        if (generation != generation(key)) {
            throw new IllegalStateException("The resource generation changed.");
        }
        if (configurationBusy && configurationResource(key)) {
            throw new IllegalStateException("Configuration maintenance is in progress.");
        }
    }

    public void beginAtomic() {
        requireWritable();
        if (atomic.get() != null) {
            throw new IllegalStateException("Nested storage unit of work.");
        }
        atomic.set(new LinkedHashMap<>());
    }

    public synchronized void beginShutdownCapture() {
        if (atomic.get() != null) {
            throw new IllegalStateException("Shutdown cannot interrupt a storage unit of work.");
        }
        atomic.set(new LinkedHashMap<>());
        closingCapture = true;
    }

    public void endAtomic(boolean success) {
        final Map<String, String> unit = atomic.get();
        atomic.remove();
        if (success && unit != null) {
            synchronized (this) {
                for (Map.Entry<String, String> entry : unit.entrySet()) {
                    final String key = entry.getKey();
                    if (entry.getValue() == null) {
                        cache.remove(key);
                        dirty.remove(key);
                        deleted.add(key);
                        generations.put(key, generation(key) + 1);
                    } else {
                        cache.put(key, entry.getValue());
                        dirty.put(key, entry.getValue());
                        deleted.remove(key);
                    }
                    sequences.put(key, ++sequence);
                    modified.put(key, sequence);
                }
            }
        }
    }

    public boolean inAtomic() {
        return atomic.get() != null;
    }

    public synchronized void write(String key, String value) {
        requireWritable();
        if (configurationBusy && configurationResource(key)) {
            throw new IllegalStateException("Configuration maintenance is in progress.");
        }
        if (atomic.get() != null) {
            atomic.get().put(key, value);
            return;
        }
        cache.put(key, value);
        dirty.put(key, value);
        deleted.remove(key);
        sequences.put(key, ++sequence);
        modified.put(key, sequence);
    }

    public synchronized void delete(String key) {
        requireWritable();
        if (configurationBusy && configurationResource(key)) {
            throw new IllegalStateException("Configuration maintenance is in progress.");
        }
        if (atomic.get() != null) {
            atomic.get().put(key, null);
            return;
        }
        cache.remove(key);
        generations.put(key, generation(key) + 1);
        dirty.remove(key);
        deleted.add(key);
        sequences.put(key, ++sequence);
        modified.put(key, sequence);
    }

    private void cycle() {
        if (closed) {
            return;
        }
        try {
            persist();
            synchronized (this) {
                degraded = false;
            }
        } catch (Exception e) {
            final boolean first;
            synchronized (this) {
                first = !degraded;
                degraded = true;
            }
            if (first) {
                logger.severe(
                        "Persistent backend unavailable; pending writes are retained and new"
                                + " mutations are suspended.");
            }
        }
    }

    private void persist() throws Exception {
        final Map<String, String> writes;
        final Set<String> removals;
        final Map<String, Long> submitted;
        synchronized (this) {
            writes = new LinkedHashMap<>(dirty);
            removals = new LinkedHashSet<>(deleted);
            submitted = new HashMap<>(sequences);
        }
        // JDBC runs outside the cache lock so synchronous game consumers never wait for SQL.
        if (System.nanoTime() - lastHeartbeat > TimeUnit.SECONDS.toNanos(10)) {
            engine.heartbeat();
            lastHeartbeat = System.nanoTime();
        }
        if (!writes.isEmpty() || !removals.isEmpty()) {
            engine.commit(writes, removals);
            synchronized (this) {
                for (String key : writes.keySet()) {
                    if (Objects.equals(submitted.get(key), sequences.get(key))) {
                        dirty.remove(key);
                        sequences.remove(key);
                    }
                }
                for (String key : removals) {
                    if (Objects.equals(submitted.get(key), sequences.get(key))) {
                        deleted.remove(key);
                        sequences.remove(key);
                    }
                }
            }
        }
    }

    public CompletableFuture<Void> flush() {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        worker.execute(
                () -> {
                    try {
                        persist();
                        synchronized (BackendSession.this) {
                            degraded = false;
                        }
                        result.complete(null);
                    } catch (Exception e) {
                        synchronized (BackendSession.this) {
                            degraded = true;
                        }
                        result.completeExceptionally(
                                new IllegalStateException("Backend flush failed.", e));
                    }
                });
        return result;
    }

    public CompletableFuture<Object> check() {
        return configurationTask(
                () -> {
                    persist();
                    engine.check();
                    return status();
                });
    }

    public CompletableFuture<Void> reloadConfiguration() {
        return configurationOperation(null, false);
    }

    public boolean managed(String key) {
        return key != null && (sqlConfiguration || !configurationResource(key));
    }

    public static boolean configurationResource(String key) {
        return !key.equals("core/@names")
                && !key.startsWith("core/userdata/")
                && !key.startsWith("core/warps/")
                && !key.equals("core/jail.yml")
                && !key.equals("core/spawn.yml");
    }

    public boolean available() {
        return !closed && !degraded && !closingCapture;
    }

    public CompletableFuture<Object> configurationTask(java.util.concurrent.Callable<Object> task) {
        final CompletableFuture<Object> result = new CompletableFuture<>();
        worker.execute(
                () -> {
                    try {
                        result.complete(task.call());
                    } catch (Exception failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    public CompletableFuture<Void> publishConfiguration(Map<String, String> revision) {
        return configurationOperation(new LinkedHashMap<>(revision), true);
    }

    public CompletableFuture<Void> publishConfiguration(
            Map<String, String> revision, long expected) {
        return configurationOperation(new LinkedHashMap<>(revision), true, expected);
    }

    private CompletableFuture<Void> configurationOperation(
            Map<String, String> revision, boolean publish) {
        return configurationOperation(revision, publish, -1);
    }

    private CompletableFuture<Void> configurationOperation(
            Map<String, String> revision, boolean publish, long expected) {
        synchronized (this) {
            requireWritable();
            if (!sqlConfiguration || configurationBusy) {
                throw new IllegalStateException("SQL configuration is inactive or busy.");
            }
            configurationBusy = true;
        }
        final CompletableFuture<Void> result = new CompletableFuture<>();
        worker.execute(
                () -> {
                    try {
                        persist();
                        final Map<String, String> loaded;
                        if (publish) {
                            if (expected >= 0 && engine.configurationRevision() != expected) {
                                throw new net.essentialsx.storage.StorageConflictException(
                                        "The configuration draft is based on an older revision.");
                            }
                            ConfigurationRevision.validate(revision);
                            final Set<String> removals = new LinkedHashSet<>();
                            for (String key : snapshot().keySet()) {
                                if (configurationResource(key) && !revision.containsKey(key)) {
                                    removals.add(key);
                                }
                            }
                            engine.commit(revision, removals);
                            loaded = revision;
                        } else {
                            loaded = engine.loadAll();
                            ConfigurationRevision.validate(loaded);
                        }
                        synchronized (this) {
                            cache.keySet().removeIf(BackendSession::configurationResource);
                            for (Map.Entry<String, String> entry : loaded.entrySet()) {
                                if (configurationResource(entry.getKey())) {
                                    cache.put(entry.getKey(), entry.getValue());
                                    modified.put(entry.getKey(), ++sequence);
                                }
                            }
                        }
                        result.complete(null);
                    } catch (Exception e) {
                        result.completeExceptionally(e);
                    } finally {
                        configurationBusy = false;
                    }
                });
        return result;
    }

    public CompletableFuture<Object> stageConfiguration() {
        return configurationTask(
                () -> {
                    if (!sqlConfiguration) {
                        throw new IllegalStateException("Configuration is stored in local YAML.");
                    }
                    persist();
                    final File folder = ConfigurationRevision.staging(this);
                    if (folder.exists()) {
                        throw new IllegalStateException("A configuration draft already exists.");
                    }
                    final Map<String, String> revision = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : snapshot().entrySet()) {
                        if (configurationResource(entry.getKey())) {
                            revision.put(entry.getKey(), entry.getValue());
                        }
                    }
                    new YamlEngine(new ResourceCatalog(folder))
                            .commit(revision, Collections.emptySet());
                    java.nio.file.Files.write(
                            catalog.local("backend-staging/expected-revision.txt").toPath(),
                            Long.toString(engine.configurationRevision())
                                    .getBytes(StandardCharsets.UTF_8));
                    return "Configuration draft created in " + folder;
                });
    }

    public String status() {
        synchronized (this) {
            return (degraded ? "DEGRADED" : "READY")
                    + "; "
                    + engine.description()
                    + "; pending="
                    + (dirty.size() + deleted.size());
        }
    }

    public DocumentRepository repository(String prefix) {
        return new RepositoryView(prefix);
    }

    private final class RepositoryView
            implements UserRepository, ConfigurationRepository, SharedStateRepository {
        private final String prefix;

        private RepositoryView(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public CompletableFuture<Optional<String>> read(String key) {
            return CompletableFuture.completedFuture(
                    Optional.ofNullable(BackendSession.this.read(prefix + key)));
        }

        @Override
        public CompletableFuture<Map<String, String>> enumerate() {
            final Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : snapshot().entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    values.put(e.getKey().substring(prefix.length()), e.getValue());
                }
            }
            return CompletableFuture.completedFuture(values);
        }

        @Override
        public CompletableFuture<Void> write(String key, String value) {
            try {
                BackendSession.this.write(prefix + key, value);
                return flush();
            } catch (Exception e) {
                final CompletableFuture<Void> failure = new CompletableFuture<>();
                failure.completeExceptionally(e);
                return failure;
            }
        }

        @Override
        public CompletableFuture<Void> delete(String key) {
            try {
                BackendSession.this.delete(prefix + key);
                return flush();
            } catch (Exception e) {
                final CompletableFuture<Void> failure = new CompletableFuture<>();
                failure.completeExceptionally(e);
                return failure;
            }
        }

        @Override
        public CompletableFuture<Void> flush() {
            return BackendSession.this.flush();
        }
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        final CompletableFuture<Void> result = new CompletableFuture<>();
        worker.execute(
                () -> {
                    try {
                        persist();
                        engine.close();
                        result.complete(null);
                    } catch (Exception e) {
                        try {
                            emergencyExport();
                        } catch (Exception recoveryFailure) {
                            logger.severe("The emergency recovery export also failed.");
                        }
                        try {
                            engine.close();
                        } catch (Exception ignored) {
                        }
                        result.completeExceptionally(e);
                    }
                });
        worker.shutdown();
        try {
            result.get(shutdownSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            recoverAndClose(timeout);
            throw timeout;
        } catch (InterruptedException interrupted) {
            recoverAndClose(interrupted);
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            current = null;
        }
    }

    private void recoverAndClose(Exception cause) {
        try {
            emergencyExport();
        } catch (Exception recoveryFailure) {
            cause.addSuppressed(recoveryFailure);
            logger.severe("The emergency recovery export also failed.");
        } finally {
            worker.shutdownNow();
            final Thread closer =
                    new Thread(
                            () -> {
                                try {
                                    engine.close();
                                } catch (Exception failure) {
                                    logger.severe("Backend close could not confirm lease release.");
                                }
                            },
                            "EssentialsBackendClose");
            closer.setDaemon(true);
            closer.start();
        }
    }

    private void emergencyExport() throws Exception {
        final File backup =
                new File(
                        catalog.core(), "backend-recovery/" + System.currentTimeMillis() + "/core");
        new YamlEngine(new ResourceCatalog(backup)).commit(snapshot(), Collections.emptySet());
        logger.severe("Unconfirmed storage changes were exported for manual recovery: " + backup);
    }
}
