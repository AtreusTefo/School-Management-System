package com.example.tracker.service;

import org.springframework.lang.NonNull;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * One place to turn an empty Optional into a thrown exception, with the result
 * honestly declared @NonNull.
 *
 * THE PROBLEM THIS SOLVES
 * -----------------------
 * `Optional<T>.orElseThrow()` already guarantees this at runtime - it returns
 * the value or the method never returns at all. The problem is at compile
 * time: `java.util.Optional` is a plain JDK class with none of the null-safety
 * annotations this project's null analysis understands. A method declared
 * `@NonNull` that ends in `return optional.orElseThrow(...);` is still flagged,
 * because the compiler is being asked to trust a guarantee the JDK's own type
 * says nothing about.
 *
 * The usual response is a `@SuppressWarnings` at that return statement - but
 * that is the same three lines of reasoning, repeated at every "look this up
 * or fail" method in the service layer, which is exactly the shape of
 * duplication this project avoids elsewhere.
 *
 * Routing every one of them through here instead gives the compiler something
 * it CAN verify without any annotation at all: after
 * `if (value == null) throw ...;`, ordinary flow-sensitive null analysis marks
 * `value` non-null for the rest of the method - that is core null-checking,
 * not a trust exercise. One method carries the JDK boundary; every caller
 * receives a real, checked @NonNull guarantee, and no suppression appears
 * anywhere in production code.
 */
final class Require {

    private Require() {
        // Static helper; never instantiated.
    }

    @NonNull
    static <T> T orThrow(Optional<T> optional, Supplier<? extends RuntimeException> exceptionSupplier) {
        T value = optional.orElse(null);
        if (value == null) {
            throw exceptionSupplier.get();
        }
        return value;
    }
}
