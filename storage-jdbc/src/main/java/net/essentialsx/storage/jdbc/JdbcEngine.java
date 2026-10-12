package net.essentialsx.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.essentialsx.storage.StorageEngine;
import net.essentialsx.storage.StorageConflictException;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Uses one fenced writer and immutable documents at the JDBC boundary. */
public final class JdbcEngine implements StorageEngine {
    private final HikariDataSource pool;
    private final String prefix;
    private final String owner;
    private final String engine;
    private long dataSet;
    private long profile;
    private long fencing;
    private long activeRevision;
    private String importRun;
    private final Map<String,String> documents = new LinkedHashMap<>();
    private final Map<String,Long> versions = new HashMap<>();
    private PendingCommit uncertainCommit;
    private final long preloadBudget;
    private long preloadMillis;
    private final Runnable afterCommit;

    private static final class PendingCommit {
        final String token = UUID.randomUUID().toString();
        final Map<String,String> documents;
        final Map<String,Long> versions;
        long revision;
        PendingCommit(Map<String,String> documents, Map<String,Long> versions, long revision) {
            this.documents=documents; this.versions=versions; this.revision=revision;
        }
    }

    private void accept(PendingCommit batch) {
        activeRevision=batch.revision;
        versions.clear(); versions.putAll(batch.versions);
        documents.clear(); documents.putAll(batch.documents);
    }

    private void resolveCommit() throws Exception {
        if(uncertainCommit==null) { return; }
        try(Connection c=pool.getConnection()) {
            c.setAutoCommit(false); fence(c);
            try(PreparedStatement s=c.prepareStatement("SELECT last_commit FROM " + t("backend_leases") + " WHERE data_set_id=?")) {
                s.setLong(1,dataSet);
                try(ResultSet r=s.executeQuery()) {
                    if(r.next() && uncertainCommit.token.equals(r.getString(1))) { accept(uncertainCommit); }
                }
            }
            c.commit(); uncertainCommit=null;
        }
    }

    public JdbcEngine(Map<String,String> options) throws Exception {
        this(options,() -> { });
    }

    JdbcEngine(Map<String,String> options,Runnable afterCommit) throws Exception {
        this.afterCommit=afterCommit;
        preloadBudget=Long.parseLong(options.getOrDefault("preloadBytes","268435456"));
        if(preloadBudget<1024) { throw new IllegalArgumentException("Invalid preload memory budget."); }
        prefix = options.get("prefix");
        if (prefix == null || !prefix.matches("[a-z][a-z0-9_]{0,23}")) { throw new IllegalArgumentException("Invalid SQL prefix."); }
        engine = options.get("engine");
        if (!Arrays.asList("mysql","mariadb").contains(engine)) { throw new IllegalArgumentException("Invalid SQL engine."); }
        owner = options.get("instance") + "-" + UUID.randomUUID();
        String host = options.get("host");
        String database = options.get("database");
        if (!host.matches("[A-Za-z0-9.:-]+") || !database.matches("[A-Za-z0-9_]+")) { throw new IllegalArgumentException("Invalid SQL address or database."); }
        int port = Integer.parseInt(options.get("port"));
        if (port < 1 || port > 65535) { throw new IllegalArgumentException("Invalid SQL port."); }
        HikariConfig config = new HikariConfig();
        config.setPoolName("EssentialsBackend");
        config.setMaximumPoolSize(Integer.parseInt(options.get("pool")));
        config.setConnectionTimeout(Long.parseLong(options.get("timeout")));
        config.setJdbcUrl("jdbc:" + engine + "://" + host + ":" + port + "/" + database);
        config.setDriverClassName(engine.equals("mysql") ? "com.mysql.cj.jdbc.Driver" : "org.mariadb.jdbc.Driver");
        config.setUsername(options.get("username"));
        config.setPassword(options.get("password"));
        config.addDataSourceProperty("sslMode", options.get("tls"));
        if(engine.equals("mysql") && host.equals("127.0.0.1") && options.get("tls").equals("DISABLED")) {
            config.addDataSourceProperty("allowPublicKeyRetrieval", "true");
        }
        config.addDataSourceProperty("connectTimeout", options.get("timeout"));
        config.addDataSourceProperty("socketTimeout", options.get("timeout"));
        pool = new HikariDataSource(config);
        try (Connection c = pool.getConnection()) {
            String lockName = "essx_" + NodeCodec.hex(NodeCodec.hash(database + ":" + prefix)).substring(0, 48);
            try (PreparedStatement s = c.prepareStatement("SELECT GET_LOCK(?,30)")) {
                s.setString(1, lockName);
                try (ResultSet r = s.executeQuery()) { if (!r.next() || r.getInt(1) != 1) { throw new IllegalStateException("Schema lock is unavailable."); } }
            }
            try {
                Schema.migrate(c, prefix);
                dataSet = identity(c, "data_sets", options.get("dataset"));
                profile = identity(c, "config_profiles", options.get("profile"));
            } finally {
                try (PreparedStatement s = c.prepareStatement("SELECT RELEASE_LOCK(?)")) { s.setString(1, lockName); s.execute(); }
            }
            c.setAutoCommit(false);
            execute(c, "INSERT IGNORE INTO " + t("backend_leases") + "(data_set_id,owner,fencing,expires_at) VALUES(?,'',0,'2000-01-01')", dataSet);
            try (PreparedStatement s = c.prepareStatement("SELECT owner,fencing,expires_at>NOW(6) FROM " + t("backend_leases") + " WHERE data_set_id=? FOR UPDATE")) {
                s.setLong(1, dataSet);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    if (r.getBoolean(3)) { throw new IllegalStateException("The data set already has an active writer."); }
                    fencing = r.getLong(2) + 1;
                }
            }
            execute(c, "UPDATE " + t("backend_leases") + " SET owner=?,fencing=?,expires_at=DATE_ADD(NOW(6),INTERVAL 30 SECOND) WHERE data_set_id=?", owner, fencing, dataSet);
            c.commit();
        } catch (Exception failure) {
            pool.close();
            throw failure;
        }
    }

    private String t(String name) { return prefix + name; }

    private long identity(Connection c, String table, String name) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) { throw new IllegalArgumentException("Invalid storage identity."); }
        execute(c, "INSERT IGNORE INTO " + t(table) + "(name) VALUES(?)", name);
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM " + t(table) + " WHERE name=?")) {
            s.setString(1, name);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    private static void execute(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) { s.setObject(i + 1, values[i]); }
            s.executeUpdate();
        }
    }

    private void fence(Connection c) throws Exception {
        int updated;
        try (PreparedStatement s = c.prepareStatement("UPDATE " + t("backend_leases") + " SET expires_at=DATE_ADD(NOW(6),INTERVAL 30 SECOND) WHERE data_set_id=? AND owner=? AND fencing=?")) {
            s.setLong(1, dataSet); s.setString(2, owner); s.setLong(3, fencing); updated = s.executeUpdate();
        }
        if (updated != 1) { throw new IllegalStateException("Storage lease lost; persistent mutations are suspended."); }
    }

    @Override
    public void heartbeat() throws Exception {
        try (Connection c = pool.getConnection()) { fence(c); }
    }

    @Override
    public boolean initialized() throws Exception {
        try (Connection c = pool.getConnection(); PreparedStatement s = c.prepareStatement("SELECT initialized FROM " + t("data_sets") + " WHERE id=?")) {
            s.setLong(1, dataSet); try (ResultSet r = s.executeQuery()) { r.next(); return r.getBoolean(1); }
        }
    }

    @Override
    public void markInitialized() throws Exception {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false); fence(c);
            execute(c, "UPDATE " + t("data_sets") + " SET initialized=TRUE WHERE id=?", dataSet);
            c.commit();
        }
    }

    @Override
    public int beginImport(String hash) throws Exception {
        try(Connection c=pool.getConnection()) {
            c.setAutoCommit(false); fence(c);
            try(PreparedStatement s=c.prepareStatement("SELECT id,manifest_hash,progress,state FROM " + t("import_runs") + " WHERE data_set_id=? ORDER BY updated_at DESC LIMIT 1 FOR UPDATE")) {
                s.setLong(1,dataSet);
                try(ResultSet r=s.executeQuery()) {
                    if(r.next()) {
                        if(!r.getString(2).equals(hash) || r.getString(4).equals("COMPLETE")) { throw new IllegalStateException("The target import is populated or belongs to another source manifest."); }
                        importRun=r.getString(1); int progress=r.getInt(3); c.commit(); return progress;
                    }
                }
            }
            try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM " + t("users") + " WHERE data_set_id=?")) {
                s.setLong(1,dataSet); try(ResultSet r=s.executeQuery()) { r.next(); if(r.getLong(1)!=0 || activeRevision!=0) { throw new IllegalStateException("Import requires an empty target dataset and configuration profile."); } }
            }
            try(PreparedStatement s=c.prepareStatement("SELECT initialized FROM " + t("data_sets") + " WHERE id=?")) {
                s.setLong(1,dataSet); try(ResultSet r=s.executeQuery()) { r.next(); if(r.getBoolean(1)) { throw new IllegalStateException("Import requires an uninitialized target."); } }
            }
            importRun=UUID.randomUUID().toString();
            execute(c,"INSERT INTO " + t("import_runs") + "(id,data_set_id,manifest_hash,state,progress) VALUES(?,?,?,'RUNNING',0)",importRun,dataSet,hash);
            c.commit(); return 0;
        }
    }

    @Override
    public void advanceImport(int progress,boolean completed) throws Exception {
        try(Connection c=pool.getConnection()) {
            c.setAutoCommit(false); fence(c);
            execute(c,"UPDATE " + t("import_runs") + " SET progress=?,state=?,updated_at=NOW(6) WHERE id=? AND data_set_id=?",progress,completed?"COMPLETE":"RUNNING",importRun,dataSet);
            if(completed) { execute(c,"UPDATE " + t("data_sets") + " SET initialized=TRUE WHERE id=?",dataSet); }
            c.commit();
        }
    }

    private static byte[] uuid(String name) {
        UUID value = UUID.fromString(name);
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static String uuid(byte[] value) {
        ByteBuffer b = ByteBuffer.wrap(value);
        return new UUID(b.getLong(), b.getLong()).toString();
    }

    @Override
    public Map<String,String> loadAll() throws Exception {
        resolveCommit();
        long started=System.nanoTime(), documentBytes=0;
        long previousRevision=activeRevision;
        Map<String,Long> loadedVersions=new HashMap<>(versions);
        Map<String,String> result = new LinkedHashMap<>();
        Map<Long,CommentedConfigurationNode> users = new LinkedHashMap<>();
        Map<Long,String> keys = new HashMap<>();
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            fence(c);
            try (PreparedStatement s = c.prepareStatement("SELECT active_revision FROM " + t("config_profiles") + " WHERE id=?")) {
                s.setLong(1, profile); try (ResultSet r = s.executeQuery()) { r.next(); activeRevision = r.getLong(1); }
            }
            long cursor = 0;
            while (true) {
                int count = 0;
                try (PreparedStatement s = c.prepareStatement("SELECT id,uuid,revision FROM " + t("users") + " WHERE data_set_id=? AND deleted=FALSE AND id>? ORDER BY id LIMIT 500")) {
                    s.setLong(1, dataSet); s.setLong(2, cursor);
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            cursor = r.getLong(1);
                            String key = "core/userdata/" + uuid(r.getBytes(2)) + ".yml";
                            users.put(cursor, CommentedConfigurationNode.root()); keys.put(cursor, key);
                            loadedVersions.put(key, r.getLong(3)); count++;
                        }
                    }
                }
                if (count == 0) { break; }
                checkMemory();
            }
            readUserRows(c, "user_extras", "user_id,extras_yaml,checksum,codec_version", (r,n) -> {
                verifyDocument(r.getString(2),r.getString(3),r.getInt(4)); n.mergeFrom(NodeCodec.decode(r.getString(2)));
            }, users);
            readUserRows(c,"user_state","*", StateCodec::load,users);
            readUserRows(c, "user_balances", "user_id,amount_exact", (r,n) -> n.node("money").set(r.getString(2)), users);
            readUserRows(c, "user_homes", "*", (r,n) -> n.node("homes",r.getString("entry_key")).set(EntryCodec.load("user_homes",r)), users);
            readUserRows(c, "user_mail", "*", (r,n) -> n.node("mail",r.getInt("ordinal_no")).set(EntryCodec.load("user_mail",r)), users);
            readUserRows(c, "user_ignores", "*", (r,n) -> n.node("ignore",r.getInt("ordinal_no")).set(EntryCodec.load("user_ignores",r)), users);
            readUserRows(c, "user_cooldowns", "*", (r,n) -> {
                String key = r.getString("entry_key");
                if (key.startsWith("kit:")) { n.node("timestamps","kits",key.substring(4)).set(EntryCodec.load("user_cooldowns",r)); }
                else { n.node("timestamps","command-cooldowns",r.getInt("ordinal_no")).set(EntryCodec.load("user_cooldowns",r)); }
            }, users);
            for (Map.Entry<Long,CommentedConfigurationNode> entry : users.entrySet()) {
                String document=NodeCodec.encode(entry.getValue()); documentBytes+=document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if(documentBytes>preloadBudget) { throw new IllegalStateException("Storage preload exceeds its document budget."); }
                checkMemory(); result.put(keys.get(entry.getKey()), document);
            }
            CommentedConfigurationNode names = CommentedConfigurationNode.root();
            try (PreparedStatement s = c.prepareStatement("SELECT n.name,u.uuid FROM " + t("user_names") + " n JOIN " + t("users") + " u ON u.id=n.user_id WHERE n.data_set_id=? AND u.deleted=FALSE")) {
                s.setLong(1, dataSet); try (ResultSet r = s.executeQuery()) { while (r.next()) { names.node(r.getString(1)).set(uuid(r.getBytes(2))); } }
            }
            result.put("core/@names", NodeCodec.encode(names));
            for (String table : new String[]{"warps","jails","spawns"}) {
                try (PreparedStatement s = c.prepareStatement("SELECT * FROM " + t(table) + " WHERE data_set_id=?")) {
                    s.setLong(1, dataSet);
                    try (ResultSet r = s.executeQuery()) {
                        Map<String,CommentedConfigurationNode> grouped = new LinkedHashMap<>();
                        while (r.next()) {
                            CommentedConfigurationNode node = grouped.computeIfAbsent(r.getString("resource_key"), k -> CommentedConfigurationNode.root());
                            String format=r.getString("entry_format");
                            if(format.equals("root")) { node.mergeFrom(NodeCodec.decode(r.getString("value_yaml"))); }
                            else if(format.equals("location")) { node.node(table,r.getString("entry_key")).set(EntryCodec.load(table,r)); }
                            else if (table.equals("warps")) { node.set(EntryCodec.load(table,r)); }
                            else { node.node(r.getString("entry_key")).set(NodeCodec.decode(r.getString("value_yaml"))); }
                        }
                        for (Map.Entry<String,CommentedConfigurationNode> e : grouped.entrySet()) { result.put(e.getKey(),NodeCodec.encode(e.getValue())); }
                    }
                }
            }
            CommentedConfigurationNode core = CommentedConfigurationNode.root(), geo = CommentedConfigurationNode.root();
            boolean corePresent=false,geoPresent=false;
            for (String table : configTables()) {
                try (PreparedStatement s = c.prepareStatement("SELECT path,value_yaml FROM " + t(table) + " WHERE profile_id=? AND revision=?")) {
                    s.setLong(1, profile); s.setLong(2, activeRevision);
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            if(table.equals("config_geoip")) { geoPresent=true; } else { corePresent=true; }
                            Object[] path = NodeCodec.decode(r.getString(1)).getList(String.class).toArray();
                            (table.equals("config_geoip") ? geo : core).node(path).set(NodeCodec.decode(r.getString(2)));
                        }
                    }
                }
            }
            if (corePresent) { result.put("core/config.yml", NodeCodec.encode(core)); }
            if (geoPresent) { result.put("geoip/config.yml", NodeCodec.encode(geo)); }
            try (PreparedStatement s = c.prepareStatement("SELECT resource_key,document,checksum,codec_version FROM " + t("resource_documents") + " WHERE profile_id=? AND revision=?")) {
                s.setLong(1,profile); s.setLong(2,activeRevision);
                try (ResultSet r = s.executeQuery()) { while (r.next()) { verifyDocument(r.getString(2),r.getString(3),r.getInt(4)); result.put(r.getString(1),r.getString(2)); } }
            }
            if(activeRevision>0) {
                try(PreparedStatement s=c.prepareStatement("SELECT checksum,codec_version,state FROM "+t("config_revisions")+" WHERE profile_id=? AND revision=?")) {
                    s.setLong(1,profile); s.setLong(2,activeRevision);
                    try(ResultSet r=s.executeQuery()) {
                        if(!r.next() || !r.getString(3).equals("PUBLISHED")) { throw new IllegalStateException("The active configuration revision is incomplete."); }
                        if(r.getInt(2)==2 && !r.getString(1).equals(revisionChecksum(result))) { throw new IllegalStateException("Configuration revision checksum mismatch."); }
                        if(r.getInt(2)<1 || r.getInt(2)>2) { throw new IllegalStateException("Unsupported configuration revision codec."); }
                    }
                }
            }
            c.commit();
        } catch(Exception failure) {
            activeRevision=previousRevision; throw failure;
        }
        versions.clear(); versions.putAll(loadedVersions);
        documents.clear(); documents.putAll(result);
        preloadMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        return new LinkedHashMap<>(result);
    }

    private void checkMemory() {
        Runtime runtime=Runtime.getRuntime();
        if(runtime.totalMemory()-runtime.freeMemory()>runtime.maxMemory()*0.75) { throw new IllegalStateException("Storage preload exceeds the process memory budget."); }
    }

    private interface RowReader { void apply(ResultSet r, CommentedConfigurationNode n) throws Exception; }

    private void readUserRows(Connection c, String table, String columns, RowReader reader, Map<Long,CommentedConfigurationNode> users) throws Exception {
        try (PreparedStatement s = c.prepareStatement("SELECT " + columns + " FROM " + t(table) + " WHERE user_id IN (SELECT id FROM " + t("users") + " WHERE data_set_id=? AND deleted=FALSE)")) {
            s.setLong(1,dataSet);
            try (ResultSet r = s.executeQuery()) { int count=0; while (r.next()) { reader.apply(r, users.get(r.getLong(1))); if(++count%100==0) { checkMemory(); } } }
        }
    }

    private static String[] configTables() {
        return new String[]{"config_core","config_chat","config_protect","config_antibuild","config_spawn","config_geoip"};
    }

    @Override
    public void commit(Map<String,String> values, Set<String> deleted) throws Exception {
        // Resolve an acknowledged-late COMMIT before constructing the next revision.
        resolveCommit();
        Map<String,String> changes=new LinkedHashMap<>(values);
        changes.entrySet().removeIf(e -> Objects.equals(e.getValue(),documents.get(e.getKey())));
        Set<String> removals=new LinkedHashSet<>(deleted);
        removals.removeIf(key -> !documents.containsKey(key));
        values=changes; deleted=removals;
        if(values.isEmpty() && deleted.isEmpty()) { heartbeat(); return; }
        Map<String,String> next = new LinkedHashMap<>(documents);
        next.putAll(values); for (String key : deleted) { next.remove(key); }
        boolean configChanged = values.keySet().stream().anyMatch(JdbcEngine::configResource) || deleted.stream().anyMatch(JdbcEngine::configResource);
        Map<String,Long> newVersions = new HashMap<>(versions);
        PendingCommit batch=new PendingCommit(next,newVersions,activeRevision);
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                fence(c);
                List<String> userKeys = new ArrayList<>();
                for (String key : values.keySet()) { if (key.startsWith("core/userdata/")) { userKeys.add(key); } }
                for (String key : deleted) { if (key.startsWith("core/userdata/") && !userKeys.contains(key)) { userKeys.add(key); } }
                Collections.sort(userKeys);
                List<String> identityUsers=new ArrayList<>();
                for(String key:userKeys) { if(!documents.containsKey(key) || deleted.contains(key)) { identityUsers.add(key); } }
                for (String key : userKeys) { saveUser(c,key,values.get(key),newVersions); }
                if (next.containsKey("core/@names") && (!identityUsers.isEmpty() || values.containsKey("core/@names"))) { saveNames(c, next.get("core/@names"),identityUsers); }
                for (String key : values.keySet()) { if (stateTable(key) != null) { saveState(c,key,values.get(key)); } }
                for (String key : deleted) { if (stateTable(key) != null) { saveState(c,key,null); } }
                long nextRevision = activeRevision;
                if (configChanged) { nextRevision = publish(c,next); }
                batch.revision=nextRevision;
                execute(c,"UPDATE " + t("backend_leases") + " SET last_commit=? WHERE data_set_id=? AND owner=? AND fencing=?",batch.token,dataSet,owner,fencing);
                uncertainCommit=batch;
                c.commit();
                afterCommit.run();
                accept(batch); uncertainCommit=null;
            } catch (Exception failure) {
                try { c.rollback(); } catch(Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    private static boolean configResource(String key) {
        return !key.equals("core/@names") && !key.startsWith("core/userdata/") && stateTable(key) == null;
    }

    private static String stateTable(String key) {
        if (key.startsWith("core/warps/")) { return "warps"; }
        if (key.equals("core/jail.yml")) { return "jails"; }
        if (key.equals("core/spawn.yml")) { return "spawns"; }
        return null;
    }

    private void saveUser(Connection c, String key, String yaml, Map<String,Long> newVersions) throws Exception {
        String identity = key.substring("core/userdata/".length(), key.length()-4);
        byte[] uuid = uuid(identity);
        long id, revision;
        boolean tombstone;
        execute(c,"INSERT IGNORE INTO " + t("users") + "(data_set_id,uuid) VALUES(?,?)",dataSet,uuid);
        try (PreparedStatement s = c.prepareStatement("SELECT id,revision,deleted FROM " + t("users") + " WHERE data_set_id=? AND uuid=? FOR UPDATE")) {
            s.setLong(1,dataSet); s.setBytes(2,uuid);
            try (ResultSet r=s.executeQuery()) { r.next(); id=r.getLong(1); revision=r.getLong(2); tombstone=r.getBoolean(3); }
        }
        if (revision != versions.getOrDefault(key,0L) && !(tombstone && !versions.containsKey(key) && !documents.containsKey(key))) { throw new StorageConflictException("Player revision conflict."); }
        if (yaml == null) {
            execute(c,"UPDATE " + t("users") + " SET deleted=TRUE,revision=revision+1,generation=generation+1 WHERE id=?",id);
            for (String table : new String[]{"user_state","user_balances","user_homes","user_mail","user_ignores","user_cooldowns","user_extras","user_names"}) { execute(c,"DELETE FROM " + t(table) + " WHERE " + (table.equals("user_names") ? "user_id" : "user_id") + "=?",id); }
            newVersions.put(key,revision+1); return;
        }
        CommentedConfigurationNode node=NodeCodec.decode(yaml), old=NodeCodec.decode(documents.getOrDefault(key,""));
        CommentedConfigurationNode extras=node.copy();
        StateCodec.save(c,prefix,id,node,old,extras);
        if (!node.node("money").virtual()) {
            String exact=node.node("money").getString();
            if (!Objects.equals(exact,old.node("money").getString()) || node.node("baltop-exempt").getBoolean() != old.node("baltop-exempt").getBoolean()) {
                BigDecimal amount=new BigDecimal(exact), indexed=null;
                BigDecimal significant=amount.stripTrailingZeros();
                if(significant.scale()<=30 && (long)significant.precision()-significant.scale()<=35) {
                    try { BigDecimal candidate=amount.setScale(30,java.math.RoundingMode.UNNECESSARY); if (candidate.precision()<=65) { indexed=candidate; } } catch (ArithmeticException ignored) { }
                }
                execute(c,"INSERT INTO " + t("user_balances") + "(user_id,data_set_id,amount_exact,amount_index,rank_eligible) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE amount_exact=VALUES(amount_exact),amount_index=VALUES(amount_index),rank_eligible=VALUES(rank_eligible)",id,dataSet,exact,indexed,!node.node("baltop-exempt").getBoolean());
            }
            extras.removeChild("money");
        } else if(!old.node("money").virtual()) { execute(c,"DELETE FROM " + t("user_balances") + " WHERE user_id=?",id); }
        saveCollection(c,"user_homes",id,node.node("homes"),old.node("homes"),"",false);
        saveCollection(c,"user_mail",id,node.node("mail"),old.node("mail"),"",true);
        saveCollection(c,"user_ignores",id,node.node("ignore"),old.node("ignore"),"",true);
        saveCollection(c,"user_cooldowns",id,node.node("timestamps","kits"),old.node("timestamps","kits"),"kit:",false);
        saveCollection(c,"user_cooldowns",id,node.node("timestamps","command-cooldowns"),old.node("timestamps","command-cooldowns"),"command:",true);
        stripCollection(extras.node("homes"),node.node("homes"),false);
        stripCollection(extras.node("mail"),node.node("mail"),true);
        stripCollection(extras.node("ignore"),node.node("ignore"),true);
        stripCollection(extras.node("timestamps","kits"),node.node("timestamps","kits"),false);
        stripCollection(extras.node("timestamps","command-cooldowns"),node.node("timestamps","command-cooldowns"),true);
        upsertDocument(c,"user_extras","extras_yaml",id,NodeCodec.encode(extras));
        execute(c,"UPDATE " + t("users") + " SET name=?,npc=?,deleted=FALSE,revision=revision+1 WHERE id=?",node.node("last-account-name").getString(),node.node("npc").getBoolean(),id);
        newVersions.put(key,revision+1);
    }

    private static final String[] STATE_KEYS={"nickname","teleportenabled","teleportauto","godmode","flymode","muted","mute-reason","jailed","jail","ip-address","afk","geolocation","socialspy","npc","last-account-name","powertoolsenabled","accepting-pay","confirm-pay","confirm-clear","baltop-exempt","shouting"};

    private static String stateDocument(CommentedConfigurationNode node) throws Exception {
        CommentedConfigurationNode state=CommentedConfigurationNode.root();
        for(String key:STATE_KEYS) { if(!node.node(key).virtual()) { state.node(key).set(node.node(key)); } }
        return NodeCodec.encode(state);
    }

    private void upsertDocument(Connection c,String table,String column,long id,String document) throws Exception {
        execute(c,"INSERT INTO " + t(table) + "(user_id," + column + ",checksum,codec_version) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE " + column + "=VALUES(" + column + "),checksum=VALUES(checksum),codec_version=1",id,document,NodeCodec.hex(NodeCodec.hash(document)));
    }

    private static void verifyDocument(String document,String checksum,int codec) throws Exception {
        if(codec!=1 || (checksum!=null && !checksum.equals(NodeCodec.hex(NodeCodec.hash(document))))) { throw new IllegalStateException("Storage document checksum or codec mismatch."); }
    }

    private Map<String,String> entries(CommentedConfigurationNode node,String kind,boolean list) throws Exception {
        Map<String,String> values=new LinkedHashMap<>();
        if(list) { int i=0; for(CommentedConfigurationNode item:node.childrenList()) { values.put(kind+i++,NodeCodec.encode(item)); } }
        else { for(Map.Entry<Object,CommentedConfigurationNode> e:node.childrenMap().entrySet()) { if(e.getKey() instanceof String) { values.put(kind+e.getKey(),NodeCodec.encode(e.getValue())); } } }
        return values;
    }

    private static void stripCollection(CommentedConfigurationNode residual,CommentedConfigurationNode original,boolean list) {
        if(list && original.isList() && !original.empty()) { residual.parent().removeChild(residual.key()); }
        if(!list && original.isMap() && !original.empty()) {
            for(Object key:original.childrenMap().keySet()) { if(key instanceof String) { residual.removeChild(key); } }
            if(residual.empty()) { residual.parent().removeChild(residual.key()); }
        }
    }

    private void saveCollection(Connection c,String table,long id,CommentedConfigurationNode node,CommentedConfigurationNode old,String kind,boolean list) throws Exception {
        Map<String,String> next=entries(node,kind,list), previous=entries(old,kind,list);
        for(String key:previous.keySet()) { if(!next.containsKey(key)) { execute(c,"DELETE FROM " + t(table) + " WHERE user_id=? AND entry_hash=?",id,NodeCodec.hash(key)); } }
        int ordinal=0;
        for(Map.Entry<String,String> e:next.entrySet()) {
            if(!Objects.equals(e.getValue(),previous.get(e.getKey()))) {
                execute(c,"INSERT INTO " + t(table) + "(user_id,entry_hash,entry_key,ordinal_no,value_yaml) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE ordinal_no=VALUES(ordinal_no),value_yaml=VALUES(value_yaml)",id,NodeCodec.hash(e.getKey()),e.getKey(),ordinal,EntryCodec.residual(table,e.getValue()));
                EntryCodec.save(c,prefix,table,"user_id=? AND entry_hash=?",new Object[]{id,NodeCodec.hash(e.getKey())},e.getValue());
            }
            ordinal++;
        }
    }

    private void saveNames(Connection c,String yaml,List<String> changedUsers) throws Exception {
        CommentedConfigurationNode next=NodeCodec.decode(yaml), previous=NodeCodec.decode(documents.getOrDefault("core/@names",""));
        for(Object name:previous.childrenMap().keySet()) {
            if(next.node(name).virtual()) { execute(c,"DELETE FROM " + t("user_names") + " WHERE data_set_id=? AND name_hash=?",dataSet,NodeCodec.hash(name.toString())); }
        }
        for(Map.Entry<Object,CommentedConfigurationNode> e:next.childrenMap().entrySet()) {
            String uuid=e.getValue().getString();
            if(Objects.equals(uuid,previous.node(e.getKey()).getString()) && !changedUsers.contains("core/userdata/"+uuid+".yml")) { continue; }
            try (PreparedStatement s=c.prepareStatement("INSERT INTO " + t("user_names") + "(data_set_id,name_hash,name,user_id) SELECT ?,?,?,id FROM " + t("users") + " WHERE data_set_id=? AND uuid=? AND deleted=FALSE ON DUPLICATE KEY UPDATE name=VALUES(name),user_id=VALUES(user_id)")) {
                s.setLong(1,dataSet); s.setBytes(2,NodeCodec.hash(e.getKey().toString())); s.setString(3,e.getKey().toString()); s.setLong(4,dataSet); s.setBytes(5,uuid(uuid)); s.executeUpdate();
            }
        }
    }

    private void saveState(Connection c,String key,String yaml) throws Exception {
        String table=stateTable(key);
        if(yaml==null) { execute(c,"DELETE FROM " + t(table) + " WHERE data_set_id=? AND resource_key=?",dataSet,key); return; }
        Map<String,String> next=stateEntries(table,yaml), old=stateEntries(table,documents.get(key));
        try(PreparedStatement s=c.prepareStatement("DELETE FROM "+t(table)+" WHERE data_set_id=? AND resource_key=? AND entry_format='legacy'")) {
            s.setLong(1,dataSet); s.setString(2,key); if(s.executeUpdate()>0) { old.clear(); }
        }
        for(String entry:old.keySet()) { if(!next.containsKey(entry)) { execute(c,"DELETE FROM "+t(table)+" WHERE data_set_id=? AND entry_hash=?",dataSet,NodeCodec.hash(key+"\n"+entry)); } }
        for(Map.Entry<String,String> e:next.entrySet()) {
            if(Objects.equals(e.getValue(),old.get(e.getKey()))) { continue; }
            String format=table.equals("warps")?"warp":(e.getKey().equals("root")?"root":"location");
            String entryKey=e.getKey().startsWith("location:")?e.getKey().substring(9):e.getKey();
            byte[] hash=NodeCodec.hash(key+"\n"+e.getKey());
            String residual=format.equals("root")?e.getValue():EntryCodec.residual(table,e.getValue());
            execute(c,"INSERT INTO " + t(table) + "(data_set_id,entry_hash,resource_key,entry_key,value_yaml,entry_format) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE value_yaml=VALUES(value_yaml),entry_format=VALUES(entry_format),revision=revision+1",dataSet,hash,key,entryKey,residual,format);
            if(!format.equals("root")) { EntryCodec.save(c,prefix,table,"data_set_id=? AND entry_hash=?",new Object[]{dataSet,hash},e.getValue()); }
        }
    }

    private Map<String,String> stateEntries(String table,String yaml) throws Exception {
        Map<String,String> result=new LinkedHashMap<>();
        if(yaml==null) { return result; }
        if(table.equals("warps")) { result.put("warp",yaml); return result; }
        CommentedConfigurationNode node=NodeCodec.decode(yaml), extras=node.copy();
        for(Map.Entry<Object,CommentedConfigurationNode> entry:node.node(table).childrenMap().entrySet()) { result.put("location:"+entry.getKey(),NodeCodec.encode(entry.getValue())); }
        if(!node.node(table).empty()) { extras.removeChild(table); }
        result.put("root",NodeCodec.encode(extras)); return result;
    }

    private String ownerTable(String key,String root) {
        if(key.equals("geoip/config.yml")) { return "config_geoip"; }
        if(root.equals("chat")) { return "config_chat"; }
        if(root.equals("protect")) { return "config_protect"; }
        if(root.equals("newbies") || root.startsWith("respawn-") || root.startsWith("spawn-")) { return "config_spawn"; }
        return "config_core";
    }

    private long publish(Connection c,Map<String,String> next) throws Exception {
        try (PreparedStatement s=c.prepareStatement("SELECT active_revision FROM " + t("config_profiles") + " WHERE id=? FOR UPDATE")) {
            s.setLong(1,profile); try(ResultSet r=s.executeQuery()) { r.next(); if(r.getLong(1)!=activeRevision) { throw new StorageConflictException("Configuration revision conflict."); } }
        }
        long revision=activeRevision+1;
        execute(c,"INSERT INTO " + t("config_revisions") + "(profile_id,revision,checksum,codec_version,state,author) VALUES(?,?,?,2,'PUBLISHED',?)",profile,revision,revisionChecksum(next),owner);
        for(Map.Entry<String,String> doc:next.entrySet()) {
            if(!configResource(doc.getKey())) { continue; }
            if(doc.getKey().equals("core/config.yml") || doc.getKey().equals("geoip/config.yml")) {
                CommentedConfigurationNode configuration=NodeCodec.decode(doc.getValue());
                if(doc.getKey().equals("core/config.yml")) { configuration.removeChild("backend"); }
                if(doc.getKey().equals("core/config.yml")) {
                    for(String option:ANTIBUILD_PATHS) {
                        Object[] segments=option.split("\\.");
                        CommentedConfigurationNode value=configuration.node(segments);
                        if(!value.virtual()) {
                            insertOption(c, "config_antibuild", revision, segments, value.copy());
                            CommentedConfigurationNode parent=value.parent();
                            parent.removeChild(segments[segments.length-1]);
                        }
                    }
                }
                for(Map.Entry<Object,CommentedConfigurationNode> e:configuration.childrenMap().entrySet()) {
                    String name=e.getKey().toString();
                    if(name.equals("backend")) { continue; }
                    insertOption(c, ownerTable(doc.getKey(),name), revision, new Object[]{name}, e.getValue());
                }
                if(configuration.childrenMap().isEmpty()) { insertOption(c,doc.getKey().equals("geoip/config.yml")?"config_geoip":"config_core",revision,new Object[0],configuration); }
            } else {
                execute(c,"INSERT INTO " + t("resource_documents") + "(data_set_id,profile_id,revision,resource_hash,resource_key,document,checksum,codec_version) VALUES(?,?,?,?,?,?,?,1)",dataSet,profile,revision,NodeCodec.hash(doc.getKey()),doc.getKey(),doc.getValue(),NodeCodec.hex(NodeCodec.hash(doc.getValue())));
            }
        }
        execute(c,"UPDATE " + t("config_profiles") + " SET active_revision=? WHERE id=?",revision,profile);
        return revision;
    }

    private static String revisionChecksum(Map<String,String> documents) throws Exception {
        StringBuilder canonical=new StringBuilder();
        for(Map.Entry<String,String> entry:new TreeMap<>(documents).entrySet()) {
            if(!configResource(entry.getKey())) { continue; }
            token(canonical,entry.getKey());
            if(entry.getKey().endsWith(".yml")) {
                CommentedConfigurationNode node=NodeCodec.decode(entry.getValue());
                if(entry.getKey().equals("core/config.yml")) { node.removeChild("backend"); }
                canonicalNode(canonical,node);
            } else { token(canonical,entry.getValue()); }
        }
        return NodeCodec.hex(NodeCodec.hash(canonical.toString()));
    }

    private static void token(StringBuilder target,String value) { target.append(value.length()).append(':').append(value); }

    private static void canonicalNode(StringBuilder target,CommentedConfigurationNode node) {
        if(node.isMap()) {
            target.append('M').append(node.childrenMap().size()).append(':');
            List<Map.Entry<Object,CommentedConfigurationNode>> entries=new ArrayList<>(node.childrenMap().entrySet());
            entries.sort(Comparator.comparing(e -> e.getKey().getClass().getName()+":"+e.getKey()));
            for(Map.Entry<Object,CommentedConfigurationNode> entry:entries) { token(target,entry.getKey().getClass().getName()+":"+entry.getKey()); canonicalNode(target,entry.getValue()); }
        } else if(node.isList()) {
            target.append('L').append(node.childrenList().size()).append(':');
            for(CommentedConfigurationNode entry:node.childrenList()) { canonicalNode(target,entry); }
        } else if(node.raw()==null) { target.append('Z'); }
        else if(node.raw() instanceof Number) {
            target.append('N');
            try { token(target,new BigDecimal(node.raw().toString()).stripTrailingZeros().toString()); }
            catch(NumberFormatException special) { token(target,node.raw().toString()); }
        }
        else { token(target,node.raw().getClass().getName()); token(target,node.raw().toString()); }
    }

    private static final String[] ANTIBUILD_PATHS={"protect.disable.build","protect.disable.use","protect.alert.on-placement","protect.alert.on-use","protect.alert.on-break","protect.blacklist.placement","protect.blacklist.usage","protect.blacklist.break","protect.blacklist.piston","protect.blacklist.dispenser"};

    private void insertOption(Connection c,String table,long revision,Object[] segments,CommentedConfigurationNode value) throws Exception {
        java.util.List<String> names=new ArrayList<>();
        for(Object segment:segments) { names.add(segment.toString()); }
        String path=NodeCodec.encode(CommentedConfigurationNode.root().setList(String.class,names));
        execute(c,"INSERT INTO " + t(table) + "(profile_id,revision,path_hash,path,value_type,value_yaml) VALUES(?,?,?,?,?,?)",profile,revision,NodeCodec.hash(path),path,value.isList()?"list":value.isMap()?"map":"scalar",NodeCodec.encode(value));
    }

    @Override
    public String description() { return engine + ":" + dataSet + "/profile:" + profile + "; preload-documents=" + documents.size() + "; preload-ms=" + preloadMillis; }
    @Override
    public long configurationRevision() { return activeRevision; }

    @Override
    public void close() throws Exception {
        try (Connection c=pool.getConnection()) {
            execute(c,"UPDATE " + t("backend_leases") + " SET expires_at=NOW(6) WHERE data_set_id=? AND owner=? AND fencing=?",dataSet,owner,fencing);
        } finally { pool.close(); }
    }
}
