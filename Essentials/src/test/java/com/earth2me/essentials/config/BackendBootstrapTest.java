package com.earth2me.essentials.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earth2me.essentials.config.storage.BackendBootstrap;
import com.earth2me.essentials.config.storage.SqlTable;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

class BackendBootstrapTest {
    @Test
    void acceptsExistingConfigurationWithoutABackendSection() {
        assertDoesNotThrow(
                () -> BackendBootstrap.requireAvailableBackend(CommentedConfigurationNode.root()));
    }

    @Test
    void rejectsUnavailableMysqlInsteadOfUsingYaml() throws Exception {
        final CommentedConfigurationNode root = CommentedConfigurationNode.root();
        root.node("backend", "type").set("mysql");
        assertThrows(
                IllegalStateException.class, () -> BackendBootstrap.requireAvailableBackend(root));
        root.node("backend", "type").set("yaml");
        assertDoesNotThrow(() -> BackendBootstrap.requireAvailableBackend(root));
    }

    @Test
    void rejectsMistypedBackendSelectors() throws Exception {
        final CommentedConfigurationNode root = CommentedConfigurationNode.root();
        root.node("backend", "type").set(true);
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendBootstrap.requireAvailableBackend(root));
        root.node("backend", "type").set("myslq");
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendBootstrap.requireAvailableBackend(root));
        root.node("backend").set("mysql");
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendBootstrap.requireAvailableBackend(root));
    }

    @Test
    void rejectsUnsafeSqlIdentifiersAndKeepsAllNamesWithinTheServerLimit() {
        for (final String prefix :
                new String[]{"",
                    "a;DROP_TABLE",
                    "a`",
                    "a.b",
                    "a/",
                    "1a",
                    "é_",
                    "A_",
                    "abcdefghijklmnopqrstuvwxy"
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> SqlTable.USERS.nameWithPrefix(prefix));
        }
        assertThrows(IllegalArgumentException.class, () -> SqlTable.USERS.nameWithPrefix(null));
        for (final SqlTable table : SqlTable.values()) {
            assertTrue(table.nameWithPrefix("abcdefghijklmnopqrstuvwx").length() <= 64);
        }
    }
}
