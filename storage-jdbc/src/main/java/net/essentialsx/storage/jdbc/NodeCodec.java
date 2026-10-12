package net.essentialsx.storage.jdbc;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Preserves Configurate values without converting decimal values through double. */
public final class NodeCodec {
    private NodeCodec() { }
    public static CommentedConfigurationNode decode(String yaml) throws Exception {
        return YamlConfigurationLoader.builder().source(() -> new BufferedReader(new StringReader(yaml))).build().load();
    }
    public static String encode(CommentedConfigurationNode node) throws Exception {
        StringWriter output = new StringWriter();
        YamlConfigurationLoader.builder().sink(() -> new BufferedWriter(output)).build().save(node);
        return output.toString();
    }
    public static byte[] hash(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder();
        for (byte b : bytes) { output.append(String.format("%02x", b & 255)); }
        return output.toString();
    }
}
