package net.essentialsx.storage.jdbc;

import org.spongepowered.configurate.CommentedConfigurationNode;
import java.sql.*;
import java.util.*;

/** Assigns stable scalar fields to typed columns and keeps unusual values in extras. */
public final class StateCodec {
    public static final Map<String,String> TYPES=new LinkedHashMap<>();
    private static final Map<String,String> BASE_TYPES=new LinkedHashMap<>();
    static final Map<String,String> EXTRA_TYPES=new LinkedHashMap<>();
    static {
        for(String key:new String[]{"nickname","mute-reason","jail","ip-address","geolocation","last-account-name"}) { TYPES.put(key,"TEXT"); }
        for(String key:new String[]{"teleportenabled","teleportauto","godmode","flymode","muted","jailed","afk","socialspy","npc","powertoolsenabled","accepting-pay","confirm-pay","confirm-clear","baltop-exempt","shouting"}) { TYPES.put(key,"BOOLEAN"); }
        for(String key:new String[]{"lastteleport","lastheal","mute","jail","onlinejail","logout","login"}) { TYPES.put("timestamps."+key,"BIGINT"); }
        BASE_TYPES.putAll(TYPES);
        EXTRA_TYPES.put("npc-name","TEXT"); EXTRA_TYPES.put("last-message-reply-recipient","BOOLEAN");
        for(String location:new String[]{"lastlocation","logoutlocation"}) {
            EXTRA_TYPES.put(location+".world","TEXT"); EXTRA_TYPES.put(location+".world-name","TEXT");
            for(String field:new String[]{"x","y","z","yaw","pitch"}) { EXTRA_TYPES.put(location+"."+field,"DOUBLE"); }
        }
        TYPES.putAll(EXTRA_TYPES);
    }
    private StateCodec() { }
    public static String column(String key) { return "v_"+key.replace('-','_').replace('.','_'); }
    public static String ddl() {
        StringBuilder result=new StringBuilder("user_id BIGINT PRIMARY KEY");
        for(Map.Entry<String,String> e:BASE_TYPES.entrySet()) { result.append(",").append(column(e.getKey())).append(" ").append(e.getValue()).append(" NULL"); }
        return result.toString();
    }
    private static CommentedConfigurationNode field(CommentedConfigurationNode node,String key) { return node.node((Object[])key.split("\\.")); }
    public static void save(Connection c,String prefix,long id,CommentedConfigurationNode node,CommentedConfigurationNode old,CommentedConfigurationNode extras) throws Exception {
        StringBuilder columns=new StringBuilder("user_id"), slots=new StringBuilder("?"), updates=new StringBuilder();
        List<Object> values=new ArrayList<>(); values.add(id);
        boolean changed=false;
        for(Map.Entry<String,String> e:TYPES.entrySet()) {
            Object raw=field(node,e.getKey()).raw(), value=null;
            if(raw instanceof String && e.getValue().equals("TEXT")) { value=raw; }
            if(raw instanceof Boolean && e.getValue().equals("BOOLEAN")) { value=raw; }
            if(raw instanceof Number && e.getValue().equals("BIGINT") && !(raw instanceof Float) && !(raw instanceof Double)) { try { value=new java.math.BigDecimal(raw.toString()).longValueExact(); } catch(ArithmeticException ignored) { } }
            if((raw instanceof Float || raw instanceof Double) && e.getValue().equals("DOUBLE") && Double.isFinite(((Number)raw).doubleValue())) { value=((Number)raw).doubleValue(); }
            values.add(value);
            if(!Objects.equals(raw,field(old,e.getKey()).raw())) { changed=true; }
            String col=column(e.getKey()); columns.append(",").append(col); slots.append(",?");
            if(updates.length()>0) { updates.append(","); } updates.append(col).append("=VALUES(").append(col).append(")");
            if(value!=null) {
                CommentedConfigurationNode child=field(extras,e.getKey());
                child.parent().removeChild(child.key());
            }
        }
        if(!changed) { return; }
        try(PreparedStatement statement=c.prepareStatement("INSERT INTO "+prefix+"user_state("+columns+") VALUES("+slots+") ON DUPLICATE KEY UPDATE "+updates)) {
            for(int i=0;i<values.size();i++) { statement.setObject(i+1,values.get(i)); } statement.executeUpdate();
        }
    }
    public static void load(ResultSet row,CommentedConfigurationNode node) throws Exception {
        for(Map.Entry<String,String> e:TYPES.entrySet()) {
            Object value=row.getObject(column(e.getKey()));
            if(value!=null) {
                if(e.getValue().equals("BOOLEAN")) { value=row.getBoolean(column(e.getKey())); }
                else if(e.getValue().equals("BIGINT")) { value=row.getLong(column(e.getKey())); }
                else if(e.getValue().equals("DOUBLE")) { value=row.getDouble(column(e.getKey())); }
                else { value=row.getString(column(e.getKey())); }
                field(node,e.getKey()).set(value);
            }
        }
    }
}
