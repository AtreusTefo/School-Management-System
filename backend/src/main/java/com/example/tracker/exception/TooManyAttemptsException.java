package com.example.tracker.exception;

/**
 * Too many failed sign-in attempts, too quickly - answered as 429.
 *
 * Deliberately NOT a subclass of Spring Security's AuthenticationException.
 * GlobalExceptionHandler maps that whole family to 401, and being throttled is
 * a different statement from "those credentials are wrong": the credentials
 * were never looked at.
 *
 * The message is the same sentence for every caller and says nothing about
 * whether the username exists, how many attempts have been made, or how many
 * remain. See V19__login_attempt_throttle.sql on why any of those would
 * quietly reintroduce the account enumeration the sign-in and reset flows are
 * both built to avoid.
 */
public class TooManyAttemptsException extends RuntimeException {

    public TooManyAttemptsException(String message) {
        super(message);
    }
}
