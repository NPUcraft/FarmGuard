package com.npucraft.farmguard.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.npucraft.farmguard.TestData;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskNotificationPlannerTest {

    @Test
    void hundredHighChunksDoNotEmitHundredAlerts() {
        NotificationLimiter limiter = new NotificationLimiter();
        List<RiskAssessment> assessments = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var key = TestData.chunk(i, 0);
            assessments.add(TestData.risk(key, RiskLevel.HIGH, 80 - (i * 0.01), TestData.snapshot(key, 40, 0, 0, 0)));
        }
        var first = RiskNotificationPlanner.plan(assessments, 3, 45, limiter, 1_000L);
        assertEquals(3, first.send().size());
        assertEquals(97, first.suppressed());

        var duringCooldown = RiskNotificationPlanner.plan(assessments, 3, 45, limiter, 5_000L);
        assertEquals(0, duringCooldown.send().size());
        assertEquals(0, duringCooldown.suppressed());

        var later = RiskNotificationPlanner.plan(assessments, 3, 45, limiter, 47_000L);
        assertEquals(3, later.send().size());
        assertEquals(97, later.suppressed());
    }
}
