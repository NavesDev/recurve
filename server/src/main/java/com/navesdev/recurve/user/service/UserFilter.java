package com.navesdev.recurve.user.service;

/**
 * Listing filter (FR-06.1). {@code text} searches name and email;
 * {@code active} is optional and, when absent, matches both.
 */
public record UserFilter(String text, Boolean active) {
}
