package dev.farmguard.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NotificationLimiterTest {

    @Test
    void suppressesRepeatAlertsInsideCooldown() {
        NotificationLimiter limiter = new NotificationLimiter();
        assertTrue(limiter.allow("chunk", 1_000L, 10));
        assertFalse(limiter.allow("chunk", 5_000L, 10));
        assertTrue(limiter.allow("chunk", 12_000L, 10));
        assertEquals(11, limiter.secondsSinceFirst("chunk", 12_000L));
    }
}
