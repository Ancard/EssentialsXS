package com.earth2me.essentials.config.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.essentialsx.storage.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

class YamlRepositoriesTest {
    @TempDir File root;

    @Test
    void preservesSnapshotsAndDistinguishesAbsenceFromSuccessfulDeletion() throws Exception {
        final String key = UUID.randomUUID() + ".yml",
                document =
                        "money: '123.456789012345678901234567890123456789'\n"
                            + "unknown: [second, first]\n";
        try (YamlRepositories yaml = new YamlRepositories(new ResourceCatalog(root))) {
            final UserRepository users = yaml.users();
            assertFalse(users.read(key).get(5, TimeUnit.SECONDS).isPresent());
            users.write(key, document).get(5, TimeUnit.SECONDS);
            users.flush().get(5, TimeUnit.SECONDS);
            assertEquals(document, users.read(key).get(5, TimeUnit.SECONDS).get());
            assertTrue(users.enumerate().get(5, TimeUnit.SECONDS).containsKey(key));
            users.delete(key).get(5, TimeUnit.SECONDS);
            users.flush().get(5, TimeUnit.SECONDS);
            assertFalse(users.read(key).get(5, TimeUnit.SECONDS).isPresent());
        }
    }
}
