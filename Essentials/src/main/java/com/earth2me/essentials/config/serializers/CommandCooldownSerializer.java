package com.earth2me.essentials.config.serializers;

import com.earth2me.essentials.config.entities.CommandCooldown;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.util.regex.Pattern;

public class CommandCooldownSerializer implements TypeSerializer<CommandCooldown> {
    @Override
    public CommandCooldown deserialize(Type type, ConfigurationNode node)
            throws SerializationException {
        try {
            final String expression = node.node("pattern").getString();
            final Pattern pattern =
                    expression == null
                            ? null
                            : Pattern.compile(expression, node.node("pattern-flags").getInt());
            if (node.node("value").isNull()) {
                return null;
            }
            final Long longValue = node.node("value").getLong();
            final CommandCooldown cooldown = new CommandCooldown();
            cooldown.pattern(pattern);
            cooldown.value(longValue);
            return cooldown;
        } catch (final IllegalArgumentException ignored) {
        }
        return null;
    }

    @Override
    public void serialize(Type type, @Nullable CommandCooldown obj, ConfigurationNode node)
            throws SerializationException {
        if (obj == null || obj.isIncomplete()) {
            node.raw(null);
            return;
        }

        node.node("pattern").set(Pattern.class, obj.pattern());
        node.node("value").set(Long.class, obj.value());
        if (obj.pattern().flags() != 0) {
            node.node("pattern-flags").set(Integer.class, obj.pattern().flags());
        } else {
            node.removeChild("pattern-flags");
        }
    }
}
