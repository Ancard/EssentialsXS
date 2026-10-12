package com.earth2me.essentials.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earth2me.essentials.config.storage.ConfigurationStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class EssentialsConfigurationSnapshotTest {
    @TempDir Path directory;

    @Test
    void aQueuedSaveCannotObserveLaterUnsavedMutations() throws Exception {
        final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        final CountDownLatch secondWriteFinished = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        final AtomicReference<CommentedConfigurationNode> stored = new AtomicReference<>();
        final ConfigurationStore store =
                new ConfigurationStore() {
                @Override
                public CommentedConfigurationNode load() {
                    return createNode();
                }

                @Override
                public void save(final CommentedConfigurationNode snapshot) {
                    if (writes.incrementAndGet() == 1) {
                        firstWriteStarted.countDown();
                        try {
                            if (!releaseFirstWrite.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("The first write was not released.");
                            }
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    }
                    stored.set(snapshot.copy());
                    if (writes.get() == 2) {
                        secondWriteFinished.countDown();
                    }
                }

                @Override
                public CommentedConfigurationNode createNode() {
                    return CommentedConfigurationNode.root();
                }
            };

        final Path file = Files.createFile(directory.resolve("profile.yml"));
        final EssentialsConfiguration configuration =
                new EssentialsConfiguration(file.toFile(), null, null, store);
        configuration.load();
        try {
            configuration.setProperty("value", 1);
            configuration.save();
            assertTrue(firstWriteStarted.await(10, TimeUnit.SECONDS));
            configuration.setProperty("value", 2);
            configuration.save();
            configuration.setProperty("value", 3);
            releaseFirstWrite.countDown();
            assertTrue(secondWriteFinished.await(10, TimeUnit.SECONDS));
            assertEquals(2, stored.get().node("value").getInt());
            assertEquals(3, configuration.get("value"));
        } finally {
            releaseFirstWrite.countDown();
            EssentialsConfiguration.shutdownExecutor();
        }
    }
}
