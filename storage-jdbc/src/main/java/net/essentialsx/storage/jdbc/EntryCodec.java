package net.essentialsx.storage.jdbc;

import org.spongepowered.configurate.CommentedConfigurationNode;
import java.sql.*;
import java.util.*;

/** Stores common collection fields in columns while retaining unusual types and extension keys. */
final class EntryCodec {
    static Map<String,String> fields(String table) {
        Map<String,String> fields=new LinkedHashMap<>();
        if(Arrays.asList("user_homes","warps","jails","spawns").contains(table)) {
            for(String key:new String[]{"name","world","world-name"}) { fields.put(key,"TEXT"); }
            for(String key:new String[]{"x","y","z","yaw","pitch"}) { fields.put(key,"DOUBLE"); }
        } else if(table.equals("user_mail")) {
            for(String key:new String[]{"message","sender-name","sender-uuid"}) { fields.put(key,"LONGTEXT"); }
            for(String key:new String[]{"legacy","read"}) { fields.put(key,"BOOLEAN"); }
            for(String key:new String[]{"timestamp","expire"}) { fields.put(key,"BIGINT"); }
        } else if(table.equals("user_ignores")) { fields.put("@value","TEXT"); }
        else if(table.equals("user_cooldowns")) {
            fields.put("@value","BIGINT"); fields.put("value","BIGINT"); fields.put("pattern","TEXT");
        }
        return fields;
    }
    static String column(String key) { return "n_"+key.replace('-','_').replace('@','_'); }
    private static Object value(CommentedConfigurationNode node,String key,String type) {
        Object raw=key.equals("@value")?node.raw():node.node(key).raw();
        if((type.equals("TEXT")||type.equals("LONGTEXT")) && raw instanceof String) { return raw; }
        if(type.equals("BOOLEAN") && raw instanceof Boolean) { return raw; }
        if(type.equals("BIGINT") && raw instanceof Number && !(raw instanceof Float) && !(raw instanceof Double)) { try { return new java.math.BigDecimal(raw.toString()).longValueExact(); } catch(ArithmeticException ignored) { return null; } }
        // Integral location values remain in the residual to preserve their original YAML type.
        if(type.equals("DOUBLE") && (raw instanceof Float || raw instanceof Double) && Double.isFinite(((Number)raw).doubleValue())) { return ((Number)raw).doubleValue(); }
        return null;
    }
    static String residual(String table,String document) throws Exception {
        CommentedConfigurationNode node=NodeCodec.decode(document), extra=node.copy();
        for(Map.Entry<String,String> field:fields(table).entrySet()) {
            if(value(node,field.getKey(),field.getValue())!=null) {
                if(field.getKey().equals("@value")) { extra.raw(null); }
                else { extra.removeChild(field.getKey()); }
            }
        }
        return NodeCodec.encode(extra);
    }
    static void save(Connection c,String prefix,String table,String predicate,Object[] ids,String document) throws Exception {
        Map<String,String> fields=fields(table);
        if(fields.isEmpty()) { return; }
        CommentedConfigurationNode node=NodeCodec.decode(document);
        StringJoiner updates=new StringJoiner(",");
        for(String key:fields.keySet()) { updates.add(column(key)+"=?"); }
        try(PreparedStatement s=c.prepareStatement("UPDATE "+prefix+table+" SET "+updates+" WHERE "+predicate)) {
            int i=1;
            for(Map.Entry<String,String> field:fields.entrySet()) { s.setObject(i++,value(node,field.getKey(),field.getValue())); }
            for(Object id:ids) { s.setObject(i++,id); } s.executeUpdate();
        }
    }
    static CommentedConfigurationNode load(String table,ResultSet row) throws Exception {
        CommentedConfigurationNode node=NodeCodec.decode(row.getString("value_yaml"));
        for(Map.Entry<String,String> field:fields(table).entrySet()) {
            String column=column(field.getKey()); Object value=row.getObject(column);
            if(value==null) { continue; }
            switch(field.getValue()) {
                case "BOOLEAN": value=row.getBoolean(column); break;
                case "BIGINT": value=row.getLong(column); break;
                case "DOUBLE": value=row.getDouble(column); break;
                default: value=row.getString(column);
            }
            if(field.getKey().equals("@value")) { node.set(value); } else { node.node(field.getKey()).set(value); }
        }
        return node;
    }
}
