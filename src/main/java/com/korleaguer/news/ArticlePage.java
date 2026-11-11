package com.korleaguer.news;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기사 페이지(HTML)에서 대표 사진 주소(og:image)와 본문 글자를 꺼낸다. 요약에만 쓰고 본문은 저장하지 않는다.
 * 본문은 WordPress 기사 칸(entry-content)부터 그 뒤 공유·댓글 칸(entry-footer, 댓글) 앞까지의 문단이다
 * (MLB Trade Rumors 구조, 2026-11-11 확인). 칸을 못 찾으면 본문은 빈 문자열이다.
 */
record ArticlePage(String imageUrl, String text) {

    static final int MAX_TEXT = 8000;
    static final int MAX_IMAGE_URL = 1000;

    private static final Pattern META = Pattern.compile("(?is)<meta\\s[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(?is)([a-z:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern PARAGRAPH = Pattern.compile("(?is)<p[\\s>].*?</p>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern BODY_START =
            Pattern.compile("(?i)<[a-z]+\\s[^>]*class\\s*=\\s*[\"'][^\"']*\\bentry-content\\b[^>]*>");
    private static final String[] BODY_ENDS = {"class=\"entry-footer", "id=\"comments\"", "class=\"comments", "</article>"};

    static ArticlePage parse(byte[] html) {
        String page = new String(html, StandardCharsets.UTF_8);
        return new ArticlePage(ogImage(page), body(page));
    }

    private static String ogImage(String page) {
        Matcher meta = META.matcher(page);
        while (meta.find()) {
            String property = null;
            String content = null;
            Matcher attr = ATTR.matcher(meta.group());
            while (attr.find()) {
                String name = attr.group(1).toLowerCase(Locale.ROOT);
                String value = attr.group(3) != null ? attr.group(3) : attr.group(4);
                if (name.equals("property") || name.equals("name")) {
                    property = value.trim().toLowerCase(Locale.ROOT);
                } else if (name.equals("content")) {
                    content = FeedParser.Texts.decode(value.trim());
                }
            }
            if (("og:image".equals(property) || "og:image:secure_url".equals(property)) && usable(content)) {
                return content;
            }
        }
        return null;
    }

    private static boolean usable(String url) {
        return url != null && url.length() <= MAX_IMAGE_URL && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    private static String body(String page) {
        Matcher open = BODY_START.matcher(page);
        if (!open.find()) {
            return "";
        }
        int start = open.end();
        int end = page.length();
        for (String marker : BODY_ENDS) {
            int at = page.indexOf(marker, start);
            if (at > 0 && at < end) {
                end = at;
            }
        }
        StringBuilder text = new StringBuilder();
        Matcher p = PARAGRAPH.matcher(page.substring(start, end));
        while (p.find() && text.length() < MAX_TEXT) {
            String line = FeedParser.Texts.decode(TAG.matcher(p.group()).replaceAll(" "))
                    .replaceAll("[\\s\\u00A0]+", " ").trim();
            if (!line.isEmpty()) {
                text.append(line).append('\n');
            }
        }
        String out = text.toString().trim();
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
    }
}
