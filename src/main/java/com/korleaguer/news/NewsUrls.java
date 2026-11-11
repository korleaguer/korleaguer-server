package com.korleaguer.news;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

/** 기사 주소 다듬기(같은 기사를 한 번만 두려고)와 고유 키(SHA-256) */
final class NewsUrls {

    private NewsUrls() {
    }

    /** scheme·host를 소문자로, #조각과 추적용 매개변수(utm_*, fbclid, gclid)를 뺀다. 읽을 수 없는 주소면 #조각만 뺀다. */
    static String normalize(String link) {
        String trimmed = link.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return withoutFragment(trimmed);
            }
            String query = uri.getRawQuery() == null ? null : Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(p -> !p.isEmpty() && !isTracking(p))
                    .collect(Collectors.joining("&"));
            StringBuilder out = new StringBuilder()
                    .append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://")
                    .append(uri.getRawAuthority().toLowerCase(Locale.ROOT))
                    .append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
            if (query != null && !query.isEmpty()) {
                out.append('?').append(query);
            }
            return out.toString();
        } catch (URISyntaxException e) {
            return withoutFragment(trimmed);
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean isTracking(String param) {
        String name = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
        return name.startsWith("utm_") || name.equals("fbclid") || name.equals("gclid");
    }

    private static String withoutFragment(String link) {
        int hash = link.indexOf('#');
        return hash >= 0 ? link.substring(0, hash) : link;
    }
}
