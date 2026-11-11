package com.korleaguer.news;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기사 페이지(HTML)에서 대표 사진 주소(og:image)와 본문 글자를 꺼낸다. 요약에만 쓰고 본문은 저장하지 않는다.
 * 본문은 WordPress 기사 칸(entry-content)부터 그 뒤 공유·댓글 칸(entry-footer, 댓글) 앞까지의 문단이다
 * (MLB Trade Rumors 구조, 2026-11-11 확인). 그 칸이 없는 사이트(MLB.com·CBS Sports 등)는 article 칸(없으면 페이지 전체)의
 * 긴 문단(LONG_PARAGRAPH자 이상, 메뉴·광고 문구 같은 짧은 줄은 뺀다)을 모은다. 못 찾으면 빈 문자열이다.
 */
record ArticlePage(String imageUrl, String text, String description) {

    ArticlePage(String imageUrl, String text) {
        this(imageUrl, text, null);
    }

    static final int MAX_TEXT = 8000;
    /** 기사 칸이 없는 사이트에서 본문으로 볼 문단의 최소 글자 수 */
    static final int LONG_PARAGRAPH = 60;
    private static final Pattern ARTICLE = Pattern.compile("(?is)<article[\\s>].*?</article>");
    static final int MAX_IMAGE_URL = 1000;

    private static final Pattern META = Pattern.compile("(?is)<meta\\s[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(?is)([a-z:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern PARAGRAPH = Pattern.compile("(?is)<p[\\s>].*?</p>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern BODY_START =
            Pattern.compile("(?i)<[a-z]+\\s[^>]*class\\s*=\\s*[\"'][^\"']*\\bentry-content\\b[^>]*>");
    private static final Pattern CHARSET = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)");
    private static final String[] BODY_ENDS = {"class=\"entry-footer", "id=\"comments\"", "class=\"comments", "</article>"};

    static ArticlePage parse(byte[] html) {
        String page = new String(html, charset(html));
        return new ArticlePage(meta(page, "og:image", "og:image:secure_url"), body(page),
                FeedParser.Texts.lead(meta(page, "og:description", "description")));
    }

    /** 페이지 앞부분의 meta charset(EUC-KR 등). 없거나 모르는 이름이면 UTF-8 */
    private static Charset charset(byte[] html) {
        String head = new String(html, 0, Math.min(html.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher m = CHARSET.matcher(head);
        if (m.find()) {
            String name = m.group(1);
            if (name.toLowerCase(Locale.ROOT).startsWith("ks_c_5601")) {
                name = "MS949";
            }
            try {
                return Charset.forName(name);
            } catch (RuntimeException e) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 처음 나온 이름의 meta 값(property나 name). 사진 이름(og:image…)이면 화면에 쓸 수 있는 https 주소로 다듬는다
     * (FeedParser.Texts.imageUrl: http는 https로). 없으면 null
     */
    private static String meta(String page, String... names) {
        java.util.List<String> wanted = java.util.List.of(names);
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
            if (property != null && wanted.contains(property) && content != null && !content.isBlank()) {
                if (property.startsWith("og:image")) {
                    String url = FeedParser.Texts.imageUrl(content);
                    if (url != null) {
                        return url;
                    }
                } else {
                    return content;
                }
            }
        }
        return null;
    }

    private static String body(String page) {
        Matcher open = BODY_START.matcher(page);
        if (open.find()) {
            int start = open.end();
            int end = page.length();
            for (String marker : BODY_ENDS) {
                int at = page.indexOf(marker, start);
                if (at > 0 && at < end) {
                    end = at;
                }
            }
            return paragraphs(page.substring(start, end), 1);
        }
        // 기사 칸이 없는 사이트: article 칸들(없으면 페이지 전체)의 긴 문단만
        StringBuilder articles = new StringBuilder();
        Matcher article = ARTICLE.matcher(page);
        while (article.find()) {
            articles.append(article.group()).append('\n');
        }
        return paragraphs(articles.isEmpty() ? page : articles.toString(), LONG_PARAGRAPH);
    }

    /** 문단(p)의 글자를 줄마다 모은다. minLength보다 짧은 문단은 뺀다. MAX_TEXT자까지 */
    private static String paragraphs(String html, int minLength) {
        StringBuilder text = new StringBuilder();
        Matcher p = PARAGRAPH.matcher(html);
        while (p.find() && text.length() < MAX_TEXT) {
            String line = FeedParser.Texts.decode(TAG.matcher(p.group()).replaceAll(" "))
                    .replaceAll("[\\s\\u00A0]+", " ").trim();
            if (line.length() >= minLength) {
                text.append(line).append('\n');
            }
        }
        String out = text.toString().trim();
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
    }
}
