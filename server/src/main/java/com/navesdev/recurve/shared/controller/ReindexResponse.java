package com.navesdev.recurve.shared.controller;

/** How many documents a rebuilt search index holds (FR-01.5, FR-02.5). */
public record ReindexResponse(long indexed) {
}
