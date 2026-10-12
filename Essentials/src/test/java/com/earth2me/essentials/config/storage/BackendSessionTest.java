package com.earth2me.essentials.config.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.essentialsx.storage.StorageEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

class BackendSessionTest {
    @TempDir File root;

    private static final class MemoryEngine implements StorageEngine {
        final Map<String, String> values = new LinkedHashMap<>();
        boolean fail;
        CountDownLatch entered;
        CountDownLatch release;

        public Map<String, String> loadAll() {
            return new LinkedHashMap<>(values);
        }

        public void commit(Map<String, String> documents, Set<String> deleted) throws Exception {
            if (entered != null) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Fixture timeout.");
                }
                entered = null;
            }
            if (fail) {
                throw new IllegalStateException("Injected write failure.");
            }
            values.putAll(documents);
            for (String key : deleted) {
                values.remove(key);
            }
        }

        public void heartbeat() {
        }

        public String description() {
            return "fixture";
        }

        public boolean initialized() {
            return true;
        }

        public void markInitialized() {
        }

        public int beginImport(String hash) {
            return 0;
        }

        public void advanceImport(int progress, boolean completed) {
        }

        public void close() {
        }
    }

    private BackendSession session(MemoryEngine engine) throws Exception {
        return new BackendSession(
                root,
                CommentedConfigurationNode.root(),
                Logger.getLogger("BackendFixture"),
                engine);
    }

    @Test
    void failedFlushRetainsWritesAndRejectsNewMutationsUntilRecovery() throws Exception {
        final MemoryEngine engine = new MemoryEngine();
        try (BackendSession session = session(engine)) {
            session.write("core/userdata/fixture.yml", "money: '3'\n");
            engine.fail = true;
            assertThrows(Exception.class, () -> session.flush().get(5, TimeUnit.SECONDS));
            assertFalse(session.available());
            assertThrows(IllegalStateException.class, () -> session.write("other", "value"));
            assertEquals("money: '3'\n", session.read("core/userdata/fixture.yml"));
            engine.fail = false;
            session.flush().get(5, TimeUnit.SECONDS);
            assertTrue(session.available());
            assertEquals(session.snapshot(), engine.values);
        }
    }

    @Test
    void gameReadsAndNewSnapshotsDoNotWaitForJdbc() throws Exception {
        final MemoryEngine engine = new MemoryEngine();
        try (BackendSession session = session(engine)) {
            engine.entered = new CountDownLatch(1);
            engine.release = new CountDownLatch(1);
            session.write("user", "first");
            final java.util.concurrent.CompletableFuture<Void> flush = session.flush();
            assertTrue(engine.entered.await(5, TimeUnit.SECONDS));
            session.write("user", "second");
            assertEquals("second", session.read("user"));
            engine.release.countDown();
            flush.get(5, TimeUnit.SECONDS);
            session.flush().get(5, TimeUnit.SECONDS);
            assertEquals("second", engine.values.get("user"));
        }
    }

    @Test
    void deletionInvalidatesOlderSnapshotsAndAtomicUnitsArePublishedTogether() throws Exception {
        final MemoryEngine engine = new MemoryEngine();
        try (BackendSession session = session(engine)) {
            final long generation = session.generation("user");
            session.write("user", "old", generation);
            session.delete("user");
            assertThrows(
                    IllegalStateException.class, () -> session.write("user", "late", generation));
            session.beginAtomic();
            session.write("left", "2");
            session.write("right", "8");
            assertNull(session.read("left"));
            session.endAtomic(false);
            assertNull(session.read("right"));
            session.beginAtomic();
            session.write("left", "3");
            session.write("right", "7");
            session.endAtomic(true);
            session.flush().get(5, TimeUnit.SECONDS);
            assertEquals("3", engine.values.get("left"));
            assertEquals("7", engine.values.get("right"));
            assertFalse(engine.values.containsKey("user"));
        }
    }

    @Test
    void invalidConfigurationNeverReplacesTheActiveRevision() throws Exception {
        final MemoryEngine engine = new MemoryEngine();
        engine.values.put("core/config.yml", "teleport-delay: 2\n");
        try (BackendSession session = session(engine)) {
            assertThrows(
                    Exception.class,
                    () ->
                            session.publishConfiguration(
                                            Collections.singletonMap(
                                                    "core/config.yml", "teleport-delay: wrong\n"))
                                    .get(5, TimeUnit.SECONDS));
            assertEquals("teleport-delay: 2\n", session.read("core/config.yml"));
        }
    }

    @Test
    void staleDraftCannotReplaceTheActiveRevision() throws Exception {
        final MemoryEngine engine = new MemoryEngine();
        engine.values.put("core/config.yml", "teleport-delay: 2\n");
        try (BackendSession session = session(engine)) {
            assertThrows(
                    Exception.class,
                    () ->
                            session.publishConfiguration(
                                            Collections.singletonMap(
                                                    "core/config.yml", "teleport-delay: 3\n"),
                                            1)
                                    .get(5, TimeUnit.SECONDS));
            assertEquals("teleport-delay: 2\n", session.read("core/config.yml"));
        }
    }

    @Test
    void shutdownTimeoutExportsPendingSnapshots() throws Exception {
        final MemoryEngine engine = new MemoryEngine();
        final CommentedConfigurationNode bootstrap = CommentedConfigurationNode.root();
        bootstrap.node("backend", "mysql", "persistence", "shutdown-timeout-seconds").set(1);
        final BackendSession session =
                new BackendSession(root, bootstrap, Logger.getLogger("BackendFixture"), engine);
        engine.entered = new CountDownLatch(1);
        engine.release = new CountDownLatch(1);
        session.write("core/userdata/00000000-0000-0000-0000-000000000001.yml", "money: '3'\n");
        try {
            assertThrows(java.util.concurrent.TimeoutException.class, session::close);
            assertTrue(new File(root, "backend-recovery").isDirectory());
            assertFalse(session.available());
        } finally {
            engine.release.countDown();
        }
    }
}
