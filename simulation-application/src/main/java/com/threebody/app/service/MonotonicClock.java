package com.threebody.app.service;

/** Monotonic clock used by the publication scheduler. */
@FunctionalInterface
public interface MonotonicClock {
    long nanoTime();
}
