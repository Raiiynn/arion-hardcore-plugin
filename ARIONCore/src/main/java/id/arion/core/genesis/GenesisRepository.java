package id.arion.core.genesis;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static id.arion.core.genesis.GenesisSession.*;

/** Short-lived connections; caller serializes async operations. No Bukkit dependency. */
public final class GenesisRepository {
    private final String url;
    public GenesisRepository(Path file) { url="jdbc:sqlite:"+file.toAbsolutePath(); }
    private Connection open() throws SQLException {
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException e) { throw new SQLException(e); }
        Connection c=DriverManager.getConnection(url);
        try (var s=c.createStatement()) {
            s.execute("PRAGMA busy_timeout=3000"); s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA synchronous=FULL");
        } catch (SQLException e) { c.close(); throw e; }
        return c;
    }
    public void initialize() throws SQLException {
        try (var c=open(); var s=c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            try (var r=s.executeQuery("PRAGMA user_version")) {
                if (r.next() && r.getInt(1)>1) throw new SQLException("Unsupported Genesis schema");
            }
            c.setAutoCommit(false);
            s.execute("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, world TEXT NOT NULL, seed INTEGER NOT NULL, fingerprint TEXT NOT NULL, state TEXT NOT NULL, revision INTEGER NOT NULL, ready_at INTEGER NOT NULL, released_at INTEGER NOT NULL, started INTEGER NOT NULL, detail TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_active_session ON sessions(archived) WHERE archived=0");
            s.execute("CREATE TABLE IF NOT EXISTS participants(session TEXT NOT NULL REFERENCES sessions(id), player TEXT NOT NULL, faction TEXT NOT NULL, x REAL, y REAL, z REAL, delivery TEXT NOT NULL, attempts INTEGER NOT NULL, PRIMARY KEY(session,player))");
            s.execute("CREATE TABLE IF NOT EXISTS assignments(session TEXT NOT NULL REFERENCES sessions(id), faction TEXT NOT NULL, region TEXT NOT NULL, PRIMARY KEY(session,faction))");
            s.execute("CREATE TABLE IF NOT EXISTS rosters(session TEXT NOT NULL REFERENCES sessions(id), faction TEXT NOT NULL, member TEXT NOT NULL, PRIMARY KEY(session,faction,member))");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, session TEXT NOT NULL, at_ms INTEGER NOT NULL, detail TEXT NOT NULL)");
            s.execute("PRAGMA user_version=1"); c.commit();
        }
    }
    public void save(GenesisSession g, String audit) throws SQLException {
        try (var c=open()) {
            c.setAutoCommit(false);
            try {
                try (var p=c.prepareStatement("INSERT INTO sessions(id,world,seed,fingerprint,state,revision,ready_at,released_at,started,detail) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state,revision=excluded.revision,ready_at=excluded.ready_at,released_at=excluded.released_at,started=excluded.started,detail=excluded.detail WHERE sessions.archived=0 AND sessions.revision<=excluded.revision")) {
                    p.setString(1,g.id.toString()); p.setString(2,g.world.toString()); p.setLong(3,g.seed); p.setString(4,g.fingerprint);
                    p.setString(5,g.state.name()); p.setLong(6,g.revision); p.setLong(7,g.readyAt); p.setLong(8,g.releasedAt);
                    p.setBoolean(9,g.startIntent); p.setString(10,g.detail);
                    if (p.executeUpdate()!=1) throw new SQLException("Stale or archived Genesis write");
                }
                for (String table:List.of("participants","assignments","rosters")) try (var p=c.prepareStatement("DELETE FROM "+table+" WHERE session=?")) {
                    p.setString(1,g.id.toString()); p.executeUpdate();
                }
                try (var p=c.prepareStatement("INSERT INTO participants VALUES(?,?,?,?,?,?,?,?)")) {
                    for (var v:g.participants.values()) {
                        p.setString(1,g.id.toString()); p.setString(2,v.id().toString()); p.setString(3,v.faction());
                        if (v.spawn()==null) { p.setNull(4,Types.REAL); p.setNull(5,Types.REAL); p.setNull(6,Types.REAL); }
                        else { p.setDouble(4,v.spawn().x()); p.setDouble(5,v.spawn().y()); p.setDouble(6,v.spawn().z()); }
                        p.setString(7,v.delivery().name()); p.setInt(8,v.attempts()); p.addBatch();
                    } p.executeBatch();
                }
                try (var p=c.prepareStatement("INSERT INTO assignments VALUES(?,?,?)")) {
                    for (var e:g.regions.entrySet()) { p.setString(1,g.id.toString()); p.setString(2,e.getKey()); p.setString(3,e.getValue()); p.addBatch(); } p.executeBatch();
                }
                try (var p=c.prepareStatement("INSERT INTO rosters VALUES(?,?,?)")) {
                    for (var e:g.rosters.entrySet()) for (String member:e.getValue()) {
                        p.setString(1,g.id.toString()); p.setString(2,e.getKey()); p.setString(3,member); p.addBatch();
                    } p.executeBatch();
                }
                audit(c,g.id,audit); c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }
    public Optional<GenesisSession> load() throws SQLException {
        try (var c=open(); var s=c.createStatement(); var r=s.executeQuery("SELECT * FROM sessions WHERE archived=0")) {
            if (!r.next()) return Optional.empty();
            var g=new GenesisSession(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("world")),r.getLong("seed"),r.getString("fingerprint"),State.valueOf(r.getString("state")));
            g.revision=r.getLong("revision"); g.readyAt=r.getLong("ready_at"); g.releasedAt=r.getLong("released_at");
            g.startIntent=r.getBoolean("started"); g.detail=r.getString("detail");
            try (var p=c.prepareStatement("SELECT * FROM participants WHERE session=?")) {
                p.setString(1,g.id.toString()); try (var rows=p.executeQuery()) { while (rows.next()) {
                    UUID id=UUID.fromString(rows.getString("player"));
                    Point point=rows.getObject("x")==null ? null : new Point(rows.getDouble("x"),rows.getDouble("y"),rows.getDouble("z"));
                    g.participants.put(id,new Participant(id,rows.getString("faction"),point,Delivery.valueOf(rows.getString("delivery")),rows.getInt("attempts")));
                }}
            }
            try (var p=c.prepareStatement("SELECT * FROM assignments WHERE session=?")) {
                p.setString(1,g.id.toString()); try (var rows=p.executeQuery()) { while(rows.next()) g.regions.put(rows.getString("faction"),rows.getString("region")); }
            }
            try (var p=c.prepareStatement("SELECT * FROM rosters WHERE session=?")) {
                p.setString(1,g.id.toString()); try (var rows=p.executeQuery()) { while(rows.next()) g.rosters.computeIfAbsent(rows.getString("faction"),k->new HashSet<>()).add(rows.getString("member")); }
            }
            return Optional.of(g);
        }
    }
    public void archive(UUID id,String actor) throws SQLException {
        try (var c=open()) {
            c.setAutoCommit(false);
            try (var p=c.prepareStatement("UPDATE sessions SET archived=1 WHERE id=? AND archived=0")) {
                p.setString(1,id.toString()); if(p.executeUpdate()!=1) throw new SQLException("Session not active");
                audit(c,id,"RESET by "+actor); c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }
    private void audit(Connection c,UUID id,String detail) throws SQLException {
        try (var p=c.prepareStatement("INSERT INTO audit(session,at_ms,detail) VALUES(?,?,?)")) {
            p.setString(1,id.toString()); p.setLong(2,System.currentTimeMillis()); p.setString(3,detail); p.executeUpdate();
        }
    }
}
