package com.earth2me.essentials.config.storage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Reads text snapshots without exposing a second active filesystem source in SQL mode. */
public final class RepositoryText {
    private RepositoryText() {
    }

    public static File textFile(File root, String name) throws java.io.IOException {
        return new ResourceCatalog(root).file("core/" + name + ".txt");
    }

    public static byte[] bytes(File file) throws java.io.IOException {
        if (RepositoryFiles.managed(file)) {
            final String value =
                    BackendSession.current().read(BackendSession.current().catalog().key(file));
            if (value == null) {
                throw new java.io.FileNotFoundException("Repository text is absent.");
            }
            return value.getBytes(StandardCharsets.UTF_8);
        }
        return Files.readAllBytes(file.toPath());
    }

    public static void create(File file, java.io.InputStream input) throws java.io.IOException {
        if (input == null) {
            throw new java.io.FileNotFoundException("The embedded text template is absent.");
        }
        try (java.io.InputStream source = input;
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            if (RepositoryFiles.managed(file)) {
                BackendSession.current()
                        .write(
                                BackendSession.current().catalog().key(file),
                                new String(output.toByteArray(), StandardCharsets.UTF_8));
            } else {
                Files.write(file.toPath(), output.toByteArray());
            }
        }
    }
}
