package com.threebody.core;

/**
 * 不可变三维向量，用于位置(m)、速度(m/s)、加速度(m/s^2)与角动量等物理量。
 */
public record Vector3(double x, double y, double z) {

    public static final Vector3 ZERO = new Vector3(0, 0, 0);

    public static Vector3 of(double x, double y, double z) {
        return new Vector3(x, y, z);
    }

    public Vector3 add(Vector3 other) {
        return new Vector3(x + other.x, y + other.y, z + other.z);
    }

    public Vector3 subtract(Vector3 other) {
        return new Vector3(x - other.x, y - other.y, z - other.z);
    }

    public Vector3 multiply(double scalar) {
        return new Vector3(x * scalar, y * scalar, z * scalar);
    }

    public double dot(Vector3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vector3 cross(Vector3 other) {
        return new Vector3(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double squaredLength() {
        return x * x + y * y + z * z;
    }

    public Vector3 normalize() {
        double len = length();
        if (len == 0.0) {
            return ZERO;
        }
        return multiply(1.0 / len);
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}