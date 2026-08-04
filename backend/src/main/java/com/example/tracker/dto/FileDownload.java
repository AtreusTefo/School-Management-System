package com.example.tracker.dto;

/**
 * A document on its way out of the system.
 *
 * A plain carrier, deliberately free of any HTTP type. The service produces one
 * of these and the controller decides what headers to put around it - which is
 * the layering rule doing real work rather than being recited: the rule about
 * who may download somebody's coursework is a business rule, and it stays
 * testable without a web server because nothing here mentions one.
 */
public record FileDownload(String filename, String contentType, byte[] content) {
}
