package net.essentialsx.storage.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises only the isolated development database with randomized table prefixes. */
public final class JdbcIntegrationTest {
    private Map<String,String> options(String prefix) {
        Assumptions.assumeTrue("true".equals(System.getenv("ESSENTIALSX_RUN_DB_TESTS")));
        Map<String,String> values=new LinkedHashMap<>();
        String engine=System.getenv().getOrDefault("ESSENTIALSX_TEST_ENGINE","mysql");
        values.put("engine",engine); values.put("prefix",prefix); values.put("host","127.0.0.1"); values.put("port","3307");
        values.put("database",System.getenv().getOrDefault("ESSENTIALSX_TEST_DATABASE","essentialsxs_test")); values.put("dataset","fixture"); values.put("profile","fixture"); values.put("instance","fixture");
        values.put("pool","4"); values.put("timeout","10000"); values.put("username",System.getenv("ESSENTIALSX_DB_USER")); values.put("password",System.getenv("ESSENTIALSX_DB_PASSWORD"));
        values.put("tls",engine.equals("mariadb")?"disable":"DISABLED"); return values;
    }
    private String prefix() { return "esst_"+UUID.randomUUID().toString().replace("-","").substring(0,12)+"_"; }
    private void cleanup(Map<String,String> options) throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:"+options.get("engine")+"://127.0.0.1:3307/"+options.get("database"),options.get("username"),options.get("password")); Statement s=c.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS=0");
            List<String> tables=new ArrayList<>();
            try(ResultSet r=s.executeQuery("SHOW TABLES")) { while(r.next()) { if(r.getString(1).startsWith(options.get("prefix"))) { tables.add(r.getString(1)); } } }
            for(String table:tables) { assertTrue(table.matches("[a-z0-9_]+")); s.execute("DROP TABLE "+table); }
            s.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }
    @Test
    public void roundTripFencingAndAtomicRollback() throws Exception {
        Map<String,String> options=options(prefix());
        String id=UUID.randomUUID().toString(), key="core/userdata/"+id+".yml";
        Map<String,String> values=new LinkedHashMap<>();
        values.put(key,"last-account-name: AkiFixture\nmoney: '1234567890123.123456789012345678901234567890123'\ngodmode: true\nhomes:\n  dotted.home:\n    world: absent-world\n    x: 3.5\nmail:\n- legacy: true\n  message: hello\nignore:\n- '"+UUID.randomUUID()+"'\ntimestamps:\n  kits:\n    tools: 12345\n  command-cooldowns:\n  - pattern: '^home.*'\n    value: 999\ncustom-section:\n  nested: [first, second]\n");
        values.put("core/@names","AkiFixture: '"+id+"'\n");
        values.put("core/config.yml","teleport-delay: 2\nchat:\n  radius: 4\nprotect:\n  disable:\n    build: true\n    fire-spread: true\nnewbies:\n  kit: tools\nbackend:\n  type: mysql\n");
        values.put("geoip/config.yml","show-on-whois: true\n");
        values.put("core/warps/test.yml","name: test\nworld: absent-world\nx: 2.5\n");
        values.put("core/kits.yml","kits:\n  tools:\n    delay: 123\n    items: ['stone 3']\n");
        values.put("core/jail.yml","jails:\n  sample:\n    world: absent-world\n    x: 3.5\n    unknown: retained\nroot-extension: [one, two]\n");
        values.put("core/spawn.yml","spawns: {}\n");
        try {
            try(JdbcEngine engine=new JdbcEngine(options)) {
                engine.loadAll(); assertFalse(engine.initialized());
                assertThrows(Exception.class,() -> { try(JdbcEngine duplicate=new JdbcEngine(options)) { fail("Two writers were enabled."); } });
                engine.commit(values,Collections.emptySet()); engine.markInitialized();
                Map<String,String> loaded=engine.loadAll();
                assertEquals(NodeCodec.decode(values.get(key)).raw(),NodeCodec.decode(loaded.get(key)).raw());
                assertEquals("1234567890123.123456789012345678901234567890123",NodeCodec.decode(loaded.get(key)).node("money").getString());
                assertTrue(NodeCodec.decode(loaded.get("core/config.yml")).node("backend").virtual());
                assertEquals(NodeCodec.decode(values.get("core/jail.yml")).raw(),NodeCodec.decode(loaded.get("core/jail.yml")).raw());
                assertEquals(NodeCodec.decode(values.get("core/spawn.yml")).raw(),NodeCodec.decode(loaded.get("core/spawn.yml")).raw());
                String before=loaded.get(key);
                try(Connection c=DriverManager.getConnection("jdbc:"+options.get("engine")+"://127.0.0.1:3307/"+options.get("database"),options.get("username"),options.get("password")); Statement s=c.createStatement()) {
                    s.execute("UPDATE "+options.get("prefix")+"users SET revision=revision+1");
                }
                assertThrows(Exception.class,() -> engine.commit(Collections.singletonMap(key,"money: '1'\n"),Collections.emptySet()));
                assertEquals(NodeCodec.decode(before).raw(),NodeCodec.decode(engine.loadAll().get(key)).raw());
                engine.commit(Collections.emptyMap(),Collections.singleton(key));
                assertFalse(engine.loadAll().containsKey(key));
                engine.commit(Collections.singletonMap(key,values.get(key)),Collections.emptySet());
                assertTrue(engine.loadAll().containsKey(key));
            }
            try(JdbcEngine restart=new JdbcEngine(options)) { assertTrue(restart.initialized()); assertTrue(restart.loadAll().containsKey(key)); }
        } finally { cleanup(options); }
    }
    @Test
    public void twoPrefixesAndInterruptedImportRemainIndependent() throws Exception {
        Map<String,String> a=options(prefix()), b=options(prefix());
        a.put("pool","1");
        try {
            try(JdbcEngine left=new JdbcEngine(a); JdbcEngine right=new JdbcEngine(b)) {
                left.loadAll(); right.loadAll();
                assertEquals(0,left.beginImport("fixture-manifest"));
                left.commit(Collections.singletonMap("core/config.yml","teleport-delay: 8\n"),Collections.emptySet());
                left.advanceImport(1,false);
                assertFalse(right.loadAll().containsKey("core/config.yml"));
            }
            try(JdbcEngine resumed=new JdbcEngine(a)) {
                resumed.loadAll(); assertEquals(1,resumed.beginImport("fixture-manifest"));
                assertThrows(Exception.class,() -> resumed.beginImport("different-manifest"));
                resumed.advanceImport(1,true); assertTrue(resumed.initialized());
            }
        } finally { cleanup(a); cleanup(b); }
    }

    @Test
    public void leaseLossRejectsAWholeTransferAndExpiredOwnershipRecovers() throws Exception {
        Map<String,String> options=options(prefix());
        String first="core/userdata/"+UUID.randomUUID()+".yml", second="core/userdata/"+UUID.randomUUID()+".yml";
        try {
            try(JdbcEngine engine=new JdbcEngine(options)) {
                engine.loadAll();
                Map<String,String> initial=new LinkedHashMap<>(); initial.put(first,"money: '10'\n"); initial.put(second,"money: '20'\n");
                engine.commit(initial,Collections.emptySet());
                try(Connection c=connection(options); Statement s=c.createStatement()) { s.execute("UPDATE "+options.get("prefix")+"backend_leases SET expires_at='2000-01-01'"); }
                engine.heartbeat();
                Map<String,String> transfer=new LinkedHashMap<>(); transfer.put(first,"money: '7'\n"); transfer.put(second,"money: '23'\n");
                engine.commit(transfer,Collections.emptySet());
                try(Connection c=connection(options); Statement s=c.createStatement()) { s.execute("UPDATE "+options.get("prefix")+"backend_leases SET owner='fixture-lost',fencing=fencing+1"); }
                assertThrows(Exception.class,() -> engine.commit(initial,Collections.emptySet()));
                try(Connection c=connection(options); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT SUM(amount_index) FROM "+options.get("prefix")+"user_balances")) { assertTrue(r.next()); assertEquals(0,r.getBigDecimal(1).compareTo(new java.math.BigDecimal("30"))); }
            }
        } finally { cleanup(options); }
    }

    private Connection connection(Map<String,String> options) throws Exception {
        return DriverManager.getConnection("jdbc:"+options.get("engine")+"://127.0.0.1:3307/"+options.get("database"),options.get("username"),options.get("password"));
    }

    @Test
    public void schemaRecoveryAndTombstoneRecreationSurviveRestart() throws Exception {
        Map<String,String> options=options(prefix()); String key="core/userdata/"+UUID.randomUUID()+".yml";
        try {
            try(JdbcEngine engine=new JdbcEngine(options)) {
                engine.loadAll(); engine.commit(Collections.singletonMap(key,"money: '5'\n"),Collections.emptySet());
                engine.commit(Collections.emptyMap(),Collections.singleton(key));
            }
            try(Connection c=connection(options); Statement s=c.createStatement()) { s.execute("DELETE FROM "+options.get("prefix")+"schema_history WHERE step_id='v3_user_homes_n_world'"); }
            try(JdbcEngine engine=new JdbcEngine(options)) {
                engine.loadAll(); engine.commit(Collections.singletonMap(key,"money: '6'\n"),Collections.emptySet());
                assertEquals("6",NodeCodec.decode(engine.loadAll().get(key)).node("money").getString());
            }
            try(Connection c=connection(options); Statement s=c.createStatement()) { s.execute("UPDATE "+options.get("prefix")+"schema_history SET checksum=REPEAT('0',64) WHERE step_id='v1_users'"); }
            assertThrows(Exception.class,() -> { try(JdbcEngine rejected=new JdbcEngine(options)) { fail("A modified migration was accepted."); } });
        } finally { cleanup(options); }
    }

    @Test
    public void pagedPreloadKeepsCollectionsAndReportsMeasurements() throws Exception {
        Map<String,String> options=options(prefix());
        try {
            try(JdbcEngine engine=new JdbcEngine(options)) {
                engine.loadAll(); Map<String,String> batch=new LinkedHashMap<>();
                for(int i=0;i<501;i++) { batch.put("core/userdata/"+UUID.randomUUID()+".yml","last-account-name: fixture"+i+"\nmoney: '123.456'\nhomes:\n  sample:\n    world: missing-world\n    x: 4.5\nmail:\n- legacy: true\n  read: false\n  message: 'fixture message'\n"); }
                long start=System.nanoTime(); engine.commit(batch,Collections.emptySet()); long writeMs=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                Map<String,String> loaded=engine.loadAll(); assertEquals(502,loaded.size());
                for(String key:batch.keySet()) { assertEquals(NodeCodec.decode(batch.get(key)).raw(),NodeCodec.decode(loaded.get(key)).raw()); }
                System.out.println("Storage fixture metrics: users=501; write-ms="+writeMs+"; "+engine.description()+"; used-heap="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
            }
        } finally { cleanup(options); }
    }

    @Test
    public void lostCommitAcknowledgementDoesNotRepeatTheTransfer() throws Exception {
        Map<String,String> options=options(prefix());
        String first="core/userdata/"+UUID.randomUUID()+".yml",second="core/userdata/"+UUID.randomUUID()+".yml";
        java.util.concurrent.atomic.AtomicBoolean inject=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            try(JdbcEngine engine=new JdbcEngine(options,() -> { if(inject.getAndSet(false)) { throw new IllegalStateException("Injected lost commit acknowledgement."); } })) {
                engine.loadAll(); Map<String,String> initial=new LinkedHashMap<>(); initial.put(first,"money: '10'\n"); initial.put(second,"money: '20'\n"); engine.commit(initial,Collections.emptySet());
                Map<String,String> transfer=new LinkedHashMap<>(); transfer.put(first,"money: '7'\n"); transfer.put(second,"money: '23'\n");
                inject.set(true); assertThrows(Exception.class,() -> engine.commit(transfer,Collections.emptySet()));
                engine.commit(transfer,Collections.emptySet());
                assertEquals("7",NodeCodec.decode(engine.loadAll().get(first)).node("money").getString());
                assertEquals("23",NodeCodec.decode(engine.loadAll().get(second)).node("money").getString());
                try(Connection c=connection(options); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT MAX(revision) FROM "+options.get("prefix")+"users")) { r.next(); assertEquals(2,r.getLong(1)); }
            }
        } finally { cleanup(options); }
    }
}
