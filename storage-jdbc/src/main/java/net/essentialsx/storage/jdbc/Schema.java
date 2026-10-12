package net.essentialsx.storage.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records every idempotent DDL step and rejects a modified migration checksum. */
public final class Schema {
    private Schema() { }
    public static void migrate(Connection c, String prefix) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + prefix + "schema_history (step_id VARCHAR(64) PRIMARY KEY, checksum CHAR(64) NOT NULL) ENGINE=InnoDB");
        }
        boolean autoCommit=c.getAutoCommit();
        c.setAutoCommit(false);
        Map<String, String> steps = new LinkedHashMap<>();
        steps.put("data_sets", "id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128) COLLATE utf8mb4_bin NOT NULL UNIQUE, initialized BOOLEAN NOT NULL DEFAULT FALSE");
        steps.put("config_profiles", "id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128) COLLATE utf8mb4_bin NOT NULL UNIQUE, active_revision BIGINT NOT NULL DEFAULT 0");
        steps.put("config_revisions", "profile_id BIGINT NOT NULL, revision BIGINT NOT NULL, checksum CHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(profile_id,revision)");
        steps.put("backend_leases", "data_set_id BIGINT PRIMARY KEY, owner VARCHAR(128) NOT NULL, fencing BIGINT NOT NULL, expires_at TIMESTAMP(6) NOT NULL");
        steps.put("users", "id BIGINT AUTO_INCREMENT PRIMARY KEY, data_set_id BIGINT NOT NULL, uuid BINARY(16) NOT NULL, name TEXT NULL, npc BOOLEAN NULL, revision BIGINT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(data_set_id,uuid), UNIQUE(data_set_id,id)");
        steps.put("user_state", StateCodec.ddl() + ", FOREIGN KEY(user_id) REFERENCES " + prefix + "users(id) ON DELETE CASCADE");
        steps.put("user_balances", "user_id BIGINT PRIMARY KEY, data_set_id BIGINT NOT NULL, amount_exact LONGTEXT NOT NULL, amount_index DECIMAL(65,30) NULL, rank_eligible BOOLEAN NOT NULL DEFAULT TRUE, INDEX(data_set_id,rank_eligible,amount_index,user_id), FOREIGN KEY(data_set_id,user_id) REFERENCES " + prefix + "users(data_set_id,id) ON DELETE CASCADE");
        steps.put("user_names", "data_set_id BIGINT NOT NULL, name_hash BINARY(32) NOT NULL, name TEXT NOT NULL, user_id BIGINT NOT NULL, PRIMARY KEY(data_set_id,name_hash), INDEX(user_id), FOREIGN KEY(data_set_id,user_id) REFERENCES " + prefix + "users(data_set_id,id) ON DELETE CASCADE");
        for (String table : new String[]{"user_homes","user_mail","user_ignores","user_cooldowns"}) {
            steps.put(table, "user_id BIGINT NOT NULL, entry_hash BINARY(32) NOT NULL, entry_key TEXT NOT NULL, ordinal_no INT NOT NULL, value_yaml LONGTEXT NOT NULL, PRIMARY KEY(user_id,entry_hash), INDEX(user_id,ordinal_no), FOREIGN KEY(user_id) REFERENCES " + prefix + "users(id) ON DELETE CASCADE");
        }
        steps.put("user_extras", "user_id BIGINT PRIMARY KEY, extras_yaml LONGTEXT NOT NULL, FOREIGN KEY(user_id) REFERENCES " + prefix + "users(id) ON DELETE CASCADE");
        for (String table : new String[]{"warps","jails","spawns"}) {
            steps.put(table, "data_set_id BIGINT NOT NULL, entry_hash BINARY(32) NOT NULL, resource_key TEXT NOT NULL, entry_key TEXT NOT NULL, value_yaml LONGTEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 1, PRIMARY KEY(data_set_id,entry_hash)");
        }
        steps.put("resource_documents", "data_set_id BIGINT NOT NULL, profile_id BIGINT NOT NULL, revision BIGINT NOT NULL, resource_hash BINARY(32) NOT NULL, resource_key TEXT NOT NULL, document LONGTEXT NOT NULL, PRIMARY KEY(data_set_id,profile_id,revision,resource_hash)");
        for (String table : new String[]{"config_core","config_chat","config_protect","config_antibuild","config_spawn","config_geoip"}) {
            steps.put(table, "profile_id BIGINT NOT NULL, revision BIGINT NOT NULL, path_hash BINARY(32) NOT NULL, path TEXT NOT NULL, value_type VARCHAR(16) NOT NULL, value_yaml LONGTEXT NOT NULL, PRIMARY KEY(profile_id,revision,path_hash)");
        }
        steps.put("import_runs", "id CHAR(36) PRIMARY KEY, data_set_id BIGINT NOT NULL, manifest_hash CHAR(64) NOT NULL, state VARCHAR(16) NOT NULL, progress INT NOT NULL DEFAULT 0, updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)");
        for (Map.Entry<String,String> entry : steps.entrySet()) {
            String ddl = "CREATE TABLE IF NOT EXISTS " + prefix + entry.getKey() + " (" + entry.getValue() + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
            String checksum = NodeCodec.hex(NodeCodec.hash(ddl.substring(ddl.indexOf(" ("))));
            try (PreparedStatement query = c.prepareStatement("SELECT checksum FROM " + prefix + "schema_history WHERE step_id=?")) {
                query.setString(1, "v1_" + entry.getKey());
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        if (!checksum.equals(result.getString(1))) { throw new IllegalStateException("Schema migration checksum mismatch."); }
                        continue;
                    }
                }
            }
            String complete="CREATE TABLE IF NOT EXISTS "+prefix+entry.getKey()+" ("+freshDefinition(prefix,entry.getKey(),entry.getValue())+") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
            try (Statement s = c.createStatement()) { s.execute(complete); }
            try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + prefix + "schema_history(step_id,checksum) VALUES(?,?)")) {
                insert.setString(1, "v1_" + entry.getKey()); insert.setString(2, checksum); insert.executeUpdate();
            }
        }
        // Metadata makes this additive step resumable after DDL commits but before its ledger entry.
        String upgrade="ALTER TABLE " + prefix + "backend_leases ADD COLUMN last_commit CHAR(36) NULL";
        String upgradeChecksum=NodeCodec.hex(NodeCodec.hash("backend_leases:last_commit:CHAR(36):NULL:v2"));
        boolean recorded=false;
        try(PreparedStatement s=c.prepareStatement("SELECT checksum FROM " + prefix + "schema_history WHERE step_id='v2_commit_receipt'" ); ResultSet r=s.executeQuery()) {
            if(r.next()) {
                if(!upgradeChecksum.equals(r.getString(1))) { throw new IllegalStateException("Schema migration checksum mismatch."); }
                recorded=true;
            }
        }
        if(!recorded) {
            boolean present;
            try(ResultSet columns=c.getMetaData().getColumns(c.getCatalog(),null,prefix+"backend_leases","last_commit")) { present=columns.next(); }
            if(!present) { try(Statement s=c.createStatement()) { s.execute(upgrade); } }
            try(PreparedStatement s=c.prepareStatement("INSERT INTO " + prefix + "schema_history(step_id,checksum) VALUES('v2_commit_receipt',?)")) { s.setString(1,upgradeChecksum); s.executeUpdate(); }
        }
        for(String table:new String[]{"user_homes","user_mail","user_ignores","user_cooldowns","warps","jails","spawns"}) {
            for(Map.Entry<String,String> field:EntryCodec.fields(table).entrySet()) {
                String column=EntryCodec.column(field.getKey());
                String id="v3_"+table+"_"+column;
                String checksum=NodeCodec.hex(NodeCodec.hash(table+":"+column+":"+field.getValue()+":NULL"));
                boolean done=false;
                try(PreparedStatement s=c.prepareStatement("SELECT checksum FROM "+prefix+"schema_history WHERE step_id=?")) {
                    s.setString(1,id);
                    try(ResultSet r=s.executeQuery()) { if(r.next()) { if(!checksum.equals(r.getString(1))) { throw new IllegalStateException("Schema migration checksum mismatch."); } done=true; } }
                }
                if(done) { continue; }
                boolean present;
                try(ResultSet r=c.getMetaData().getColumns(c.getCatalog(),null,prefix+table,column)) { present=r.next(); }
                if(!present) { try(Statement s=c.createStatement()) { s.execute("ALTER TABLE "+prefix+table+" ADD COLUMN "+column+" "+field.getValue()+" NULL"); } }
                try(PreparedStatement s=c.prepareStatement("INSERT INTO "+prefix+"schema_history(step_id,checksum) VALUES(?,?)")) { s.setString(1,id); s.setString(2,checksum); s.executeUpdate(); }
            }
        }
        for(String table:new String[]{"warps","jails","spawns"}) {
            String id="v4_"+table+"_entry_format", checksum=NodeCodec.hex(NodeCodec.hash(table+":entry_format:VARCHAR(16):legacy"));
            boolean done=false;
            try(PreparedStatement s=c.prepareStatement("SELECT checksum FROM "+prefix+"schema_history WHERE step_id=?")) {
                s.setString(1,id); try(ResultSet r=s.executeQuery()) { if(r.next()) { if(!checksum.equals(r.getString(1))) { throw new IllegalStateException("Schema migration checksum mismatch."); } done=true; } }
            }
            if(done) { continue; }
            boolean present;
            try(ResultSet r=c.getMetaData().getColumns(c.getCatalog(),null,prefix+table,"entry_format")) { present=r.next(); }
            if(!present) { try(Statement s=c.createStatement()) { s.execute("ALTER TABLE "+prefix+table+" ADD COLUMN entry_format VARCHAR(16) NOT NULL DEFAULT 'legacy'"); } }
            try(PreparedStatement s=c.prepareStatement("INSERT INTO "+prefix+"schema_history(step_id,checksum) VALUES(?,?)")) { s.setString(1,id); s.setString(2,checksum); s.executeUpdate(); }
        }
        addColumn(c,prefix,"resource_documents","checksum","CHAR(64) NULL");
        addColumn(c,prefix,"resource_documents","codec_version","INT NOT NULL DEFAULT 1");
        addColumn(c,prefix,"user_extras","checksum","CHAR(64) NULL");
        addColumn(c,prefix,"user_extras","codec_version","INT NOT NULL DEFAULT 1");
        addColumn(c,prefix,"config_revisions","codec_version","INT NOT NULL DEFAULT 1");
        addColumn(c,prefix,"config_revisions","state","VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'");
        addColumn(c,prefix,"config_revisions","author","VARCHAR(128) NULL");
        addColumn(c,prefix,"users","generation","BIGINT NOT NULL DEFAULT 0");
        for(Map.Entry<String,String> field:StateCodec.EXTRA_TYPES.entrySet()) { addColumn(c,prefix,"user_state",StateCodec.column(field.getKey()),field.getValue()+" NULL"); }
        addConstraint(c,prefix,"users","fk_users_dataset","FOREIGN KEY(data_set_id) REFERENCES "+prefix+"data_sets(id)");
        addConstraint(c,prefix,"backend_leases","fk_leases_dataset","FOREIGN KEY(data_set_id) REFERENCES "+prefix+"data_sets(id)");
        addConstraint(c,prefix,"config_revisions","fk_revision_profile","FOREIGN KEY(profile_id) REFERENCES "+prefix+"config_profiles(id)");
        addConstraint(c,prefix,"resource_documents","uq_profile_document","UNIQUE(profile_id,revision,resource_hash)");
        addConstraint(c,prefix,"resource_documents","fk_resource_revision","FOREIGN KEY(profile_id,revision) REFERENCES "+prefix+"config_revisions(profile_id,revision)");
        for(String table:new String[]{"config_core","config_chat","config_protect","config_antibuild","config_spawn","config_geoip"}) {
            addConstraint(c,prefix,table,"fk_"+table+"_revision","FOREIGN KEY(profile_id,revision) REFERENCES "+prefix+"config_revisions(profile_id,revision)");
        }
        for(String table:new String[]{"warps","jails","spawns","import_runs"}) {
            addConstraint(c,prefix,table,"fk_"+table+"_dataset","FOREIGN KEY(data_set_id) REFERENCES "+prefix+"data_sets(id)");
        }
        c.commit(); c.setAutoCommit(autoCommit);
    }

    private static String freshDefinition(String prefix,String table,String base) {
        StringBuilder definition=new StringBuilder(base);
        for(Map.Entry<String,String> field:EntryCodec.fields(table).entrySet()) { definition.append(',').append(EntryCodec.column(field.getKey())).append(' ').append(field.getValue()).append(" NULL"); }
        switch(table) {
            case "backend_leases": definition.append(",last_commit CHAR(36) NULL"); break;
            case "users": definition.append(",generation BIGINT NOT NULL DEFAULT 0"); break;
            case "user_state":
                for(Map.Entry<String,String> field:StateCodec.EXTRA_TYPES.entrySet()) { definition.append(',').append(StateCodec.column(field.getKey())).append(' ').append(field.getValue()).append(" NULL"); } break;
            case "resource_documents": case "user_extras": definition.append(",checksum CHAR(64) NULL,codec_version INT NOT NULL DEFAULT 1"); break;
            case "config_revisions": definition.append(",codec_version INT NOT NULL DEFAULT 1,state VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',author VARCHAR(128) NULL"); break;
            case "warps": case "jails": case "spawns": definition.append(",entry_format VARCHAR(16) NOT NULL DEFAULT 'legacy'"); break;
            default: break;
        }
        String name=null, constraint=null;
        switch(table) {
            case "users": name="fk_users_dataset"; constraint="FOREIGN KEY(data_set_id) REFERENCES "+prefix+"data_sets(id)"; break;
            case "backend_leases": name="fk_leases_dataset"; constraint="FOREIGN KEY(data_set_id) REFERENCES "+prefix+"data_sets(id)"; break;
            case "config_revisions": name="fk_revision_profile"; constraint="FOREIGN KEY(profile_id) REFERENCES "+prefix+"config_profiles(id)"; break;
            case "resource_documents":
                definition.append(",CONSTRAINT ").append(prefix).append("uq_profile_document UNIQUE(profile_id,revision,resource_hash)");
                name="fk_resource_revision"; constraint="FOREIGN KEY(profile_id,revision) REFERENCES "+prefix+"config_revisions(profile_id,revision)"; break;
            case "warps": case "jails": case "spawns": case "import_runs": name="fk_"+table+"_dataset"; constraint="FOREIGN KEY(data_set_id) REFERENCES "+prefix+"data_sets(id)"; break;
            default:
                if(table.startsWith("config_") && !table.equals("config_profiles")) { name="fk_"+table+"_revision"; constraint="FOREIGN KEY(profile_id,revision) REFERENCES "+prefix+"config_revisions(profile_id,revision)"; }
        }
        if(name!=null) { definition.append(",CONSTRAINT ").append(prefix).append(name).append(' ').append(constraint); }
        return definition.toString();
    }

    private static boolean recorded(Connection c,String prefix,String id,String checksum) throws Exception {
        try(PreparedStatement s=c.prepareStatement("SELECT checksum FROM "+prefix+"schema_history WHERE step_id=?")) {
            s.setString(1,id); try(ResultSet r=s.executeQuery()) {
                if(!r.next()) { return false; }
                if(!checksum.equals(r.getString(1))) { throw new IllegalStateException("Schema migration checksum mismatch."); }
                return true;
            }
        }
    }

    private static void record(Connection c,String prefix,String id,String checksum) throws Exception {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO "+prefix+"schema_history(step_id,checksum) VALUES(?,?)")) { s.setString(1,id); s.setString(2,checksum); s.executeUpdate(); }
    }

    private static void addColumn(Connection c,String prefix,String table,String column,String definition) throws Exception {
        String id="v5_"+table+"_"+column, checksum=NodeCodec.hex(NodeCodec.hash(table+":"+column+":"+definition));
        if(recorded(c,prefix,id,checksum)) { return; }
        boolean present;
        try(ResultSet r=c.getMetaData().getColumns(c.getCatalog(),null,prefix+table,column)) { present=r.next(); }
        if(!present) { try(Statement s=c.createStatement()) { s.execute("ALTER TABLE "+prefix+table+" ADD COLUMN "+column+" "+definition); } }
        record(c,prefix,id,checksum);
    }

    private static void addConstraint(Connection c,String prefix,String table,String name,String definition) throws Exception {
        String id="v6_"+table+"_"+name, checksum=NodeCodec.hex(NodeCodec.hash(definition));
        if(recorded(c,prefix,id,checksum)) { return; }
        boolean present;
        try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=?")) {
            s.setString(1,prefix+table); s.setString(2,prefix+name); try(ResultSet r=s.executeQuery()) { r.next(); present=r.getInt(1)>0; }
        }
        if(!present) { try(Statement s=c.createStatement()) { s.execute("ALTER TABLE "+prefix+table+" ADD CONSTRAINT "+prefix+name+" "+definition); } }
        record(c,prefix,id,checksum);
    }
}
