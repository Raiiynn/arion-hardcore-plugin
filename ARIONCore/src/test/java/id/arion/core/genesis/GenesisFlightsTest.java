package id.arion.core.genesis;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static id.arion.core.genesis.GenesisSession.*;

class GenesisFlightsTest {
    @Test void timeoutExpiresSafetyWithoutAllowingDuplicateDispatchOrReset() {
        var flights=new GenesisFlights(Duration.ofSeconds(15));UUID id=UUID.randomUUID();flights.begin(id,1,0);
        assertTrue(flights.protecting(id,14_999_999_999L));assertTrue(flights.expired(14_999_999_999L).isEmpty());
        assertEquals(id,flights.expired(15_000_000_000L).orElseThrow());assertFalse(flights.protecting(id,15_000_000_000L));
        assertFalse(flights.isEmpty());assertThrows(IllegalStateException.class,()->flights.begin(id,2,16_000_000_000L));
        assertTrue(flights.finish(id,1));assertTrue(flights.isEmpty());
    }
    @Test void oldCallbackCannotRemoveNewGenerationFlight() {
        var flights=new GenesisFlights(Duration.ofSeconds(15));UUID id=UUID.randomUUID();flights.begin(id,1,0);flights.finish(id,1);
        flights.begin(id,2,100);assertFalse(flights.finish(id,1));assertEquals(1,flights.size());assertTrue(flights.finish(id,2));
        assertFalse(flights.finish(id,2));
    }
    @Test void concurrencyBoundIsEnforcedEvenIfCallerMisbehaves() {
        var flights=new GenesisFlights(Duration.ofSeconds(15));
        for(int i=0;i<5;i++)flights.begin(UUID.randomUUID(),1,0);
        assertThrows(IllegalStateException.class,()->flights.begin(UUID.randomUUID(),1,0));
    }
    @Test void onlyOneExplicitRetryAndNoRetryWhileInFlight() {
        var p=new Participant(UUID.randomUUID(),"a",new Point(0,80,0),Delivery.PENDING,0).attempt();
        assertThrows(IllegalStateException.class,p::attempt);
        var retried=p.delivered(Delivery.PENDING).attempt();assertEquals(2,retried.attempts());
        assertThrows(IllegalStateException.class,()->retried.delivered(Delivery.PENDING).attempt());
    }
}
