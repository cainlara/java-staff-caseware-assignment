package com.example.fanout.domain;

import java.util.Objects;

final class Checks {

    private Checks() {
    }

    static <T> T requireNonNull(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
        return value;
    }

    static int requireAtLeast(int value, int minimum, String fieldName) {
        if (value < minimum) {
            throw new IllegalArgumentException(fieldName + " must be >= " + minimum + " but was " + value);
        }
        return value;
    }

    static long requireAtLeast(long value, long minimum, String fieldName) {
        if (value < minimum) {
            throw new IllegalArgumentException(fieldName + " must be >= " + minimum + " but was " + value);
        }
        return value;
    }
}
