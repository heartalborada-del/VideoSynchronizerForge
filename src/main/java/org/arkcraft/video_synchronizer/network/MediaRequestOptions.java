package org.arkcraft.video_synchronizer.network;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated HTTP headers attached to every request for a media session. */
public record MediaRequestOptions(String headers, String cookie) {
    public static final int MAX_HEADERS_LENGTH = 8_192;
    public static final int MAX_COOKIE_LENGTH = 8_192;
    public static final MediaRequestOptions EMPTY = new MediaRequestOptions("", "");

    private static final Pattern HEADER_NAME = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "connection", "content-length", "cookie", "host", "proxy-authorization",
            "proxy-connection", "range", "transfer-encoding");

    public MediaRequestOptions {
        headers = normalizeHeaders(headers);
        cookie = normalizeCookie(cookie);
    }

    public static String normalizeHeaders(String value) {
        String source = value == null ? "" : value;
        if (source.length() > MAX_HEADERS_LENGTH) {
            throw new IllegalArgumentException(
                    "Request headers exceed " + MAX_HEADERS_LENGTH + " characters");
        }
        if (source.indexOf('\r') >= 0) {
            source = source.replace("\r\n", "\n");
            if (source.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Request headers contain an invalid line break");
            }
        }
        StringBuilder normalized = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("Each request header must use 'Name: value'");
            }
            String name = line.substring(0, separator).trim();
            String headerValue = line.substring(separator + 1).trim();
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid request header name: " + name);
            }
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Request header is managed by the player: " + name);
            }
            validateValue(headerValue, "Request header " + name);
            if (!normalized.isEmpty()) {
                normalized.append('\n');
            }
            normalized.append(name).append(": ").append(headerValue);
        }
        return normalized.toString();
    }

    public static String normalizeCookie(String value) {
        String source = value == null ? "" : value.trim();
        if (source.regionMatches(true, 0, "Cookie:", 0, "Cookie:".length())) {
            source = source.substring("Cookie:".length()).trim();
        }
        if (source.length() > MAX_COOKIE_LENGTH) {
            throw new IllegalArgumentException(
                    "Cookie exceeds " + MAX_COOKIE_LENGTH + " characters");
        }
        StringBuilder normalized = new StringBuilder(source.length());
        for (String item : source.split(";", -1)) {
            String pair = item.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Each Cookie must use 'key=value'");
            }
            String name = pair.substring(0, separator).trim();
            String cookieValue = pair.substring(separator + 1).trim();
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid Cookie key: " + name);
            }
            validateValue(cookieValue, "Cookie " + name);
            if (!normalized.isEmpty()) {
                normalized.append("; ");
            }
            normalized.append(name).append('=').append(cookieValue);
        }
        return normalized.toString();
    }

    private static void validateValue(String value, String description) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                throw new IllegalArgumentException(description + " contains a control character");
            }
        }
    }

    public boolean hasHeader(String requestedName) {
        for (String line : headers.split("\n")) {
            int separator = line.indexOf(':');
            if (separator > 0 && line.substring(0, separator).trim()
                    .equalsIgnoreCase(requestedName)) {
                return true;
            }
        }
        return false;
    }

    public String ffmpegHeaderBlock() {
        StringBuilder block = new StringBuilder(headers.length() + cookie.length() + 16);
        if (!headers.isBlank()) {
            block.append(headers.replace("\n", "\r\n")).append("\r\n");
        }
        if (!cookie.isBlank()) {
            block.append("Cookie: ").append(cookie).append("\r\n");
        }
        return block.toString();
    }
}
