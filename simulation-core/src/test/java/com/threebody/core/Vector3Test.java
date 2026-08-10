package com.threebody.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Vector3Test {

    @Test
    @DisplayName("向量加减与标量乘法")
    void basicArithmetic() {
        Vector3 a = Vector3.of(1, 2, 3);
        Vector3 b = Vector3.of(4, 5, 6);
        assertEquals(Vector3.of(5, 7, 9), a.add(b));
        assertEquals(Vector3.of(-3, -3, -3), a.subtract(b));
        assertEquals(Vector3.of(2, 4, 6), a.multiply(2));
    }

    @Test
    @DisplayName("点积与叉积遵循右手定则")
    void dotAndCross() {
        Vector3 x = Vector3.of(1, 0, 0);
        Vector3 y = Vector3.of(0, 1, 0);
        assertEquals(0.0, x.dot(y));
        assertEquals(Vector3.of(0, 0, 1), x.cross(y));
        assertEquals(Vector3.of(0, 0, -1), y.cross(x));
    }

    @Test
    @DisplayName("零向量归一化返回零向量而不是 NaN")
    void normalizeZero() {
        assertEquals(Vector3.ZERO, Vector3.ZERO.normalize());
        assertEquals(1.0, Vector3.of(3, 4, 0).normalize().length(), 1e-12);
        assertEquals(5.0, Vector3.of(3, 4, 0).length(), 1e-12);
    }

    @Test
    @DisplayName("非有限分量可被检测")
    void finiteness() {
        assertTrue(Vector3.of(1, 2, 3).isFinite());
        assertFalse(Vector3.of(Double.NaN, 0, 0).isFinite());
        assertFalse(Vector3.of(0, Double.POSITIVE_INFINITY, 0).isFinite());
    }
}