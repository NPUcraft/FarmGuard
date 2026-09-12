package dev.farmguard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.farmguard.TestData;
import dev.farmguard.model.AutomationCluster;
import dev.farmguard.model.ClusterType;
import dev.farmguard.model.LagCorrelation;
import dev.farmguard.model.ProtectionLevel;
import dev.farmguard.model.RiskLevel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WhitelistTest {

    @Test
    void clusterMemberIsExemptWhenSiblingIsListed() {
        Whitelist whitelist = new Whitelist();
        var a = TestData.chunk(4, 4);
        var b = TestData.chunk(4, 5);
        whitelist.add(a);
        AutomationCluster cluster = new AutomationCluster(
                "world:4,4",
                a.worldId(),
                "world",
                Set.of(a, b),
                ClusterType.STORAGE,
                40.0,
                40.0,
                RiskLevel.HIGH,
                LagCorrelation.NONE,
                List.of(),
                ProtectionLevel.NORMAL
        );
        assertTrue(whitelist.exemptFromProtection(b, cluster));
        assertFalse(whitelist.exemptFromProtection(TestData.chunk(9, 9), cluster));
    }

    @Test
    void clusterIdExemptsEveryMember() {
        Whitelist whitelist = new Whitelist();
        var a = TestData.chunk(1, 1);
        var b = TestData.chunk(1, 2);
        whitelist.addCluster("world:1,1");
        AutomationCluster cluster = new AutomationCluster(
                "world:1,1",
                a.worldId(),
                "world",
                Set.of(a, b),
                ClusterType.REDSTONE_MACHINE,
                40.0,
                40.0,
                RiskLevel.HIGH,
                LagCorrelation.NONE,
                List.of(),
                ProtectionLevel.NORMAL
        );
        assertTrue(whitelist.exemptFromProtection(a, cluster));
        assertTrue(whitelist.exemptFromProtection(b, cluster));
    }
}
