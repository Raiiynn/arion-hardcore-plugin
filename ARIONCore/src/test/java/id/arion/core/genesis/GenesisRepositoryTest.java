package id.arion.core.genesis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static id.arion.core.genesis.GenesisSession.*;
import static org.junit.jupiter.api.Assertions.*;

class GenesisRepositoryTest {
    @TempDir Path directory;
    GenesisSession session() {
        var s=new GenesisSession(UUID.randomUUID(),UUID.randomUUID(),1234,"config-fingerprint",State.READY);
        UUID id=UUID.randomUUID();s.participants.put(id,new Participant(id,"faction-a",new Point(-2010.5,82,44.5),Delivery.PENDING,0));
        s.regions.put("faction-a","north");s.rosters.put("faction-a",Set.of(id.toString()));s.readyAt=123456;s.revision=1;return s;
    }
    GenesisRepository repository() throws Exception {var r=new GenesisRepository(directory.resolve("genesis.db"));r.initialize();return r;}
    @Test void snapshotSurvivesReopenWithExactAssignments() throws Exception {
        var r=repository();var s=session();r.save(s,"prepare");var copy=repository().load().orElseThrow();
        assertEquals(s.id,copy.id);assertEquals(s.world,copy.world);assertEquals(s.seed,copy.seed);
        assertEquals(s.fingerprint,copy.fingerprint);assertEquals(s.participants,copy.participants);
        assertEquals(s.regions,copy.regions);assertEquals(s.rosters,copy.rosters);
    }
    @Test void onlyOneUnarchivedSessionAndResetRetainsHistory() throws Exception {
        var r=repository();var s=session();r.save(s,"first");assertThrows(SQLException.class,()->r.save(session(),"second"));
        r.archive(s.id,"console");assertTrue(r.load().isEmpty());r.save(session(),"new session");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("genesis.db"));var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM sessions")) {
            assertTrue(rows.next());assertEquals(2,rows.getInt(1));
        }
    }
    @Test void staleAndArchivedWritesCannotOverwriteLedger() throws Exception {
        var r=repository();var s=session();r.save(s,"ready");var stale=s.copy();s.revision++;s.startIntent=true;r.save(s,"start");
        assertThrows(SQLException.class,()->r.save(stale,"late callback"));assertTrue(r.load().orElseThrow().startIntent);
        r.archive(s.id,"console");assertThrows(SQLException.class,()->r.save(s,"late shutdown"));
    }
    @Test void crashWindowRetainsInFlightIntentInsteadOfClaimingArrival() throws Exception {
        var r=repository();var s=session();s.startIntent=true;s.state=State.TELEPORTING;
        s.participants.replaceAll((id,p)->p.attempt());r.save(s,"before teleport");
        var recovered=repository().load().orElseThrow();assertTrue(recovered.startIntent);
        assertEquals(Delivery.IN_FLIGHT,recovered.participants.values().iterator().next().delivery());
        assertFalse(recovered.allArrived());
    }
    @Test void failedAuditRollsBackEntireAssignmentUpdate() throws Exception {
        var r=repository();var s=session();r.save(s,"ready");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("genesis.db"));var st=c.createStatement()) {
            st.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test failure'); END");
        }
        s.revision++;s.participants.replaceAll((id,p)->p.at(new Point(4000,90,4000)));s.startIntent=true;
        assertThrows(SQLException.class,()->r.save(s,"start"));var old=r.load().orElseThrow();
        assertFalse(old.startIntent);assertEquals(-2010.5,old.participants.values().iterator().next().spawn().x());
    }
    @Test void rejectsFutureSchemaWithoutMutatingIt() throws Exception {
        repository();try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("genesis.db"));var s=c.createStatement()) {s.execute("PRAGMA user_version=99");}
        assertThrows(SQLException.class,this::repository);
    }
}
