package id.arion.core.genesis;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GenesisConfigTest {
    YamlConfiguration valid() {
        var y=new YamlConfiguration();y.set("genesis.enabled",true);y.set("genesis.opening-world","opening");y.set("genesis.destination-world","areus_s1");
        y.set("genesis.faction-ids",List.of("a","b","c"));y.set("genesis.excluded-player-uuids",List.of());y.set("genesis.excluded-regions",List.of());
        var regions=new ArrayList<Map<String,Object>>();
        for(int i=0;i<3;i++) regions.add(Map.of("id","region"+i,"min-x",i*4000,"max-x",i*4000+500,"min-z",-1000,"max-z",-500));
        y.set("genesis.candidate-regions",regions);return y;
    }
    @Test void disabledExistingInstallNeedsNoGenesisConfiguration() {assertNull(GenesisConfig.read(new YamlConfiguration()));}
    @Test void malformedEnabledIsNotSilentlyDisabled() {var y=new YamlConfiguration();y.set("genesis.enabled","yes");assertThrows(IllegalArgumentException.class,()->GenesisConfig.read(y));}
    @Test void defaultsRemainConservative() {var c=GenesisConfig.read(valid());assertFalse(c.generate());assertFalse(c.protectionReviewed());assertEquals(2000,c.distance());assertEquals(12,c.radius());}
    @Test void invalidFactionRosterAndBoundsRejected() {
        var y=valid();y.set("genesis.faction-ids",List.of("a","a","c"));assertThrows(IllegalArgumentException.class,()->GenesisConfig.read(y));
        var z=valid();z.set("genesis.chunk-loading-concurrency",100);assertThrows(IllegalArgumentException.class,()->GenesisConfig.read(z));
    }
    @Test void wrongTypesAndUnknownUuidRejected() {
        var y=valid();y.set("genesis.maximum-search-attempts","200");assertThrows(IllegalArgumentException.class,()->GenesisConfig.read(y));
        var z=valid();z.set("genesis.excluded-player-uuids",List.of("GM"));assertThrows(IllegalArgumentException.class,()->GenesisConfig.read(z));
    }
    @Test void exclusionUsesThreeDimensionsAndExactWorld() {
        var e=new GenesisConfig.Exclusion("world",-10,10,50,90,-10,10);
        assertTrue(e.contains("world",0,70,0));assertFalse(e.contains("other",0,70,0));assertFalse(e.contains("world",0,100,0));
    }
    @Test void borderAndHazardFilteringAreConservative() {
        assertFalse(SafeSpawnValidator.border(940,0,0,0,2000,64));assertTrue(SafeSpawnValidator.border(900,0,0,0,2000,64));
        for(String material:List.of("WATER","LAVA","POWDER_SNOW","CAMPFIRE","INFESTED_STONE")) assertTrue(SafeSpawnValidator.hazardous(material));
        assertFalse(SafeSpawnValidator.hazardous("GRASS_BLOCK"));
    }
}
