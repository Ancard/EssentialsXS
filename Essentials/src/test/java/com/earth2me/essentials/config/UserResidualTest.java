package com.earth2me.essentials.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.earth2me.essentials.config.holders.UserConfigHolder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class UserResidualTest {
    @TempDir Path directory;

    @Test
    void updatingMoneyPreservesUnknownFieldsAndUnavailableWorlds() throws Exception {
        final Path file = directory.resolve("user.yml");
        Files.write(
                file,
                ("money: '12.3400'\n"
                                + "custom: {nested: retained}\n"
                                + "homes:\n"
                                + "  future:\n"
                                + "    world: '00000000-0000-0000-0000-000000000001'\n"
                                + "    world-name: absent\n"
                                + "    x: 1.25\n"
                                + "    y: 60.0\n"
                                + "    z: -2.5\n"
                                + "    extension: preserved\n")
                        .getBytes(StandardCharsets.UTF_8));
        final EssentialsConfiguration configuration = new EssentialsConfiguration(file.toFile());
        configuration.load();
        final UserConfigHolder holder = configuration.getRootNode().get(UserConfigHolder.class);
        holder.money(new BigDecimal("987.6543210000"));
        configuration.setRootHolder(UserConfigHolder.class, holder);
        assertEquals("retained", configuration.getRootNode().node("custom", "nested").getString());
        assertEquals(
                "preserved",
                configuration.getRootNode().node("homes", "future", "extension").getString());
        assertEquals("absent", holder.homes().get("future").worldName());
        assertEquals(new BigDecimal("987.6543210000"), holder.money());
    }

    @Test
    void cooldownPatternFlagsSurviveHolderSerialization() throws Exception {
        final Path file = directory.resolve("cooldowns.yml");
        Files.write(
                file,
                ("timestamps:\n  command-cooldowns:\n"
                                + "  - pattern: '^HOME.*'\n    pattern-flags: 2\n    value: 999\n")
                        .getBytes(StandardCharsets.UTF_8));
        final EssentialsConfiguration configuration = new EssentialsConfiguration(file.toFile());
        configuration.load();
        final UserConfigHolder holder = configuration.getRootNode().get(UserConfigHolder.class);
        assertEquals(2, holder.timestamps().commandCooldowns().get(0).pattern().flags());
        configuration.setRootHolder(UserConfigHolder.class, holder);
        assertEquals(
                2,
                configuration
                        .getRootNode()
                        .node("timestamps", "command-cooldowns", 0, "pattern-flags")
                        .getInt());
    }
}
