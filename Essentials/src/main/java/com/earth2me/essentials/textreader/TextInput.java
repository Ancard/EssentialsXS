package com.earth2me.essentials.textreader;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.User;
import com.earth2me.essentials.utils.FormatUtil;
import com.earth2me.essentials.utils.StringUtil;

import net.ess3.api.IEssentials;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TextInput implements IText {
    private static final HashMap<String, SoftReference<TextInput>> cache = new HashMap<>();
    private final transient List<String> lines;
    private final transient List<String> chapters;
    private final transient Map<String, Integer> bookmarks;
    private final transient long lastChange;

    public TextInput(
            final CommandSource sender,
            final String filename,
            final boolean createFile,
            final IEssentials ess)
            throws IOException {

        File file = null;
        if (sender.isPlayer()) {
            final User user = ess.getUser(sender.getPlayer());
            file =
                    com.earth2me.essentials.config.storage.RepositoryText.textFile(
                            ess.getDataFolder(),
                            filename + "_" + StringUtil.sanitizeFileName(user.getName()));
            if (!com.earth2me.essentials.config.storage.RepositoryFiles.exists(file)) {
                file =
                        com.earth2me.essentials.config.storage.RepositoryText.textFile(
                                ess.getDataFolder(),
                                filename + "_" + StringUtil.sanitizeFileName(user.getGroup()));
            }
        }
        if (file == null || !com.earth2me.essentials.config.storage.RepositoryFiles.exists(file)) {
            file =
                    com.earth2me.essentials.config.storage.RepositoryText.textFile(
                            ess.getDataFolder(), filename);
        }
        if (com.earth2me.essentials.config.storage.RepositoryFiles.exists(file)) {
            lastChange = com.earth2me.essentials.config.storage.RepositoryFiles.lastModified(file);
            final boolean readFromfile;
            synchronized (cache) {
                final SoftReference<TextInput> inputRef = cache.get(file.getName());
                final TextInput input;
                if (inputRef == null
                        || (input = inputRef.get()) == null
                        || input.lastChange < lastChange) {
                    lines = new ArrayList<>();
                    chapters = new ArrayList<>();
                    bookmarks = new HashMap<>();
                    cache.put(file.getName(), new SoftReference<>(this));
                    readFromfile = true;
                } else {
                    lines = Collections.unmodifiableList(input.getLines());
                    chapters = Collections.unmodifiableList(input.getChapters());
                    bookmarks = Collections.unmodifiableMap(input.getBookmarks());
                    readFromfile = false;
                }
            }
            if (readFromfile) {
                final Reader reader =
                        new InputStreamReader(
                                new java.io.ByteArrayInputStream(
                                        com.earth2me.essentials.config.storage.RepositoryText.bytes(
                                                file)),
                                StandardCharsets.UTF_8);
                final BufferedReader bufferedReader = new BufferedReader(reader);
                try {
                    int lineNumber = 0;
                    while (bufferedReader.ready()) {
                        final String line = bufferedReader.readLine();
                        if (line == null) {
                            break;
                        }
                        if (line.length() > 1 && line.charAt(0) == '#') {
                            final String[] titles =
                                    line.substring(1).trim().replace(" ", "_").split(",");
                            chapters.add(FormatUtil.replaceFormat(titles[0]));
                            for (final String title : titles) {
                                bookmarks.put(
                                        FormatUtil.stripEssentialsFormat(
                                                title.toLowerCase(Locale.ENGLISH)),
                                        lineNumber);
                            }
                        }
                        lines.add(FormatUtil.replaceFormat(line));
                        lineNumber++;
                    }
                } finally {
                    reader.close();
                    bufferedReader.close();
                }
            }
        } else {
            lastChange = 0;
            lines = Collections.emptyList();
            chapters = Collections.emptyList();
            bookmarks = Collections.emptyMap();
            if (createFile) {
                com.earth2me.essentials.config.storage.RepositoryText.create(
                        file, ess.getResource(filename + ".txt"));
                throw new FileNotFoundException(
                        "File " + filename + ".txt does not exist. Creating one for you.");
            }
        }
    }

    @Override
    public List<String> getLines() {
        return lines;
    }

    @Override
    public List<String> getChapters() {
        return chapters;
    }

    @Override
    public Map<String, Integer> getBookmarks() {
        return bookmarks;
    }
}
