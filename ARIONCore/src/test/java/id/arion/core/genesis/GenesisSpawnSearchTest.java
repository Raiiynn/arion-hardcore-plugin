package id.arion.core.genesis;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import static id.arion.core.genesis.GenesisSession.*;
import static org.junit.jupiter.api.Assertions.*;

class GenesisSpawnSearchTest {
    List<Point> search(BiFunction<Integer,Integer,Point> terrain,int count,int radius) {
        var region=new GenesisConfig.Region("test",-100,100,-100,100,null);
        var search=new SafeSpawnValidator.GroupSearch(terrain,0,0,count,region,radius);
        int steps=0;
        while(!search.done()) { search.step();assertTrue(++steps<5000,"Search must stay bounded"); }
        return search.result();
    }
    @Test void tenDistinctPlayersFitWithinRadiusOnConnectedGround() {
        var result=search((x,z)->new Point(x+0.5,80,z+0.5),10,12);assertEquals(10,result.size());
        for(int i=0;i<result.size();i++) {
            assertTrue(result.get(i).distanceSquared(new Point(0.5,80,0.5))<=144);
            for(int j=i+1;j<result.size();j++)assertTrue(result.get(i).distanceSquared(result.get(j))>=4);
        }
    }
    @Test void cannotPlacePlayersAcrossAnUnsafeMoat() {
        var result=search((x,z)->Math.abs(x)+Math.abs(z)==1?null:new Point(x+0.5,80,z+0.5),2,12);
        assertTrue(result.isEmpty());
    }
    @Test void cliffCannotBeUsedAsConnectedGroupSurface() {
        assertTrue(search((x,z)->new Point(x+0.5,x==0 && z==0?80:84,z+0.5),2,12).isEmpty());
    }
    @Test void unsafeStartingColumnFailsWithoutUnboundedProbing() {
        var probes=new AtomicInteger();
        assertTrue(search((x,z)->{probes.incrementAndGet();return null;},10,12).isEmpty());assertEquals(1,probes.get());
    }
    @Test void seededAssignmentIsRepeatableBijectiveAndDoesNotMutateInputs() {
        var factions=List.of("a","b","c");var regions=List.of("north","east","south");
        var mapping=assignRegions(factions,regions,1234);
        assertEquals(mapping,assignRegions(factions,regions,1234));
        assertEquals(new HashSet<>(regions),new HashSet<>(mapping.values()));assertEquals(factions,new ArrayList<>(mapping.keySet()));
        assertThrows(IllegalArgumentException.class,()->assignRegions(factions,List.of("a","a","b"),42));
    }
    @Test void insufficientSpaceDoesNotReturnPartialGroup() {
        assertTrue(search((x,z)->new Point(x+0.5,80,z+0.5),10,1).isEmpty());
    }
}
