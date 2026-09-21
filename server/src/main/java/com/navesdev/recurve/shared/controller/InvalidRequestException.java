package com.navesdev.recurve.shared.controller;

/**
 * The request shape is wrong in a way Bean Validation cannot express, such
 * as a sort field outside the allowed list. Maps to 400.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
