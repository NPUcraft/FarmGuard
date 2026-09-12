package com.npucraft.farmguard.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void rateZeroAlwaysDenies() {
        TokenBucket bucket = new TokenBucket();
        assertFalse(bucket.tryConsume(0L, 0));
        assertFalse(bucket.tryConsume(1_000L, 0));
    }

    @Test
    void rapidCallsConsumeOnlyTheBurst() {
        TokenBucket bucket = new TokenBucket();
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (bucket.tryConsume(10L, 5)) {
                allowed++;
            }
        }
        assertEquals(5, allowed);
    }

    @Test
    void longPauseDoesNotAccumulateHugeBurst() {
        TokenBucket bucket = new TokenBucket();
        assertTrue(bucket.tryConsume(0L, 4));
        assertTrue(bucket.tryConsume(60_000L, 4));
        assertTrue(bucket.tokens() <= 4.0);
        int extra = 0;
        for (int i = 0; i < 20; i++) {
            if (bucket.tryConsume(60_000L, 4)) {
                extra++;
            }
        }
        assertTrue(extra <= 3);
    }

    @Test
    void highRateStillCapsToOneSecondBurst() {
        TokenBucket bucket = new TokenBucket();
        int allowed = 0;
        for (int i = 0; i < 2_000; i++) {
            if (bucket.tryConsume(0L, 1_000)) {
                allowed++;
            }
        }
        assertEquals(1_000, allowed);
    }

    @Test
    void rateDropCapsLeftoverTokens() {
        TokenBucket bucket = new TokenBucket();
        bucket.tryConsume(0L, 20);
        assertTrue(bucket.tokens() > 10.0);
        assertTrue(bucket.tryConsume(10L, 2));
        assertTrue(bucket.tokens() < 2.0);
    }
}
