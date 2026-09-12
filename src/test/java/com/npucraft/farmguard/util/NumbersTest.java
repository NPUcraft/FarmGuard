package com.npucraft.farmguard.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void pearsonDetectsPositiveCorrelation() {
        double[] x = {1, 2, 3, 4, 5, 6};
        double[] y = {2, 4, 6, 8, 10, 12};
        assertEquals(1.0, Numbers.pearson(x, y, 6), 0.0001);
    }

    @Test
    void clampWorks() {
        assertEquals(5.0, Numbers.clamp(5.0, 0.0, 10.0));
        assertEquals(0.0, Numbers.clamp(-2.0, 0.0, 10.0));
        assertEquals(10.0, Numbers.clamp(99.0, 0.0, 10.0));
        assertTrue(Numbers.pearson(new double[]{1}, new double[]{1}, 1) == 0.0);
    }
}
