package id.arion.core.genesis;

import org.junit.jupiter.api.Test;
import java.util.*;
import static id.arion.core.genesis.GenesisSession.*;
import static org.junit.jupiter.api.Assertions.*;

class GenesisSessionTest {
    GenesisSession session() { return new GenesisSession(UUID.randomUUID(),UUID.randomUUID(),42,"config",State.PREPARING); }
    @Test void legalSequenceAndDuplicateStartRejection() {
        var s=session();s.transition(State.READY);s.transition(State.THUNDER);
        assertThrows(IllegalStateException.class,()->s.transition(State.THUNDER));
        s.transition(State.TELEPORTING);s.transition(State.COMPLETED);
        assertThrows(IllegalStateException.class,()->s.transition(State.THUNDER));
    }
    @Test void cannotSkipPreparationOrResumeFailedWithoutExplicitRecovery() {
        var s=session();assertThrows(IllegalStateException.class,()->s.transition(State.THUNDER));
        s.transition(State.FAILED);assertThrows(IllegalStateException.class,()->s.transition(State.READY));
    }
    @Test void persistedCopyDoesNotObserveLaterMutations() {
        var s=session();UUID id=UUID.randomUUID();
        s.participants.put(id,new Participant(id,"a",new Point(1,70,1),Delivery.PENDING,0));
        s.rosters.put("a",new HashSet<>(Set.of(id.toString())));
        var copy=s.copy();s.participants.put(id,s.participants.get(id).attempt());s.rosters.get("a").clear();
        assertEquals(Delivery.PENDING,copy.participants.get(id).delivery());assertEquals(1,copy.rosters.get("a").size());
    }
    @Test void separationUsesEveryPlayerNotOnlyFactionCenters() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        var p=new Participant(a,"a",new Point(-12,70,0),Delivery.PENDING,0);
        var q=new Participant(b,"b",new Point(1987,90,0),Delivery.PENDING,0);
        assertFalse(separated(List.of(p,q),2000));
        assertTrue(separated(List.of(p,q.at(new Point(1988,90,0))),2000));
        assertTrue(separated(List.of(p,new Participant(b,"a",p.spawn(),Delivery.PENDING,0)),2000));
    }
    @Test void incompleteAssignmentsCannotPassSeparation() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        assertFalse(separated(List.of(new Participant(a,"a",null,Delivery.PENDING,0),new Participant(b,"b",new Point(0,80,0),Delivery.PENDING,0)),2000));
    }
    @Test void completedRequiresEveryParticipantAndAttemptsAreTracked() {
        var s=session();assertFalse(s.allArrived());UUID id=UUID.randomUUID();
        var p=new Participant(id,"a",new Point(0,80,0),Delivery.PENDING,0).attempt();
        s.participants.put(id,p);assertEquals(1,p.attempts());assertFalse(s.allArrived());
        s.participants.put(id,p.delivered(Delivery.ARRIVED));assertTrue(s.allArrived());
    }
    @Test void invalidCoordinatesRejected() { assertThrows(IllegalArgumentException.class,()->new Point(Double.NaN,70,0)); }
    @Test void durableStartIntentPreventsDuplicateTriggerBeforeThunder() {
        var s=session();s.transition(State.READY);s.armStart(false);
        assertThrows(IllegalStateException.class,()->s.armStart(false));
        s.armStart(true); // Explicit recovery may use saved points without replaying thunder.
        assertTrue(s.startIntent);
    }
    @Test void restartQuarantinesOnlyAmbiguousDeliveries() {
        var s=session();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        s.participants.put(a,new Participant(a,"a",new Point(0,80,0),Delivery.IN_FLIGHT,1));
        s.participants.put(b,new Participant(b,"b",new Point(3000,80,0),Delivery.ARRIVED,1));
        s.quarantineInFlight();assertEquals(Delivery.NEEDS_REVIEW,s.participants.get(a).delivery());
        assertEquals(Delivery.ARRIVED,s.participants.get(b).delivery());
    }
}
