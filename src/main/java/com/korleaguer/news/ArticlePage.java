package com.korleaguer.news;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기사 페이지(HTML)에서 대표 사진 주소(og:image)와 본문 글자를 꺼낸다. 요약에만 쓰고 본문은 저장하지 않는다.
 * 본문은 WordPress 기사 칸(entry-content)부터 그 뒤 공유·댓글 칸(entry-footer, 댓글) 앞까지의 문단이다
 * (MLB Trade Rumors 구조, 2026-11-11 확인). 그 칸이 없는 사이트(MLB.com·CBS Sports 등)는 article 칸(없으면 페이지 전체)의
 * 긴 문단(LONG_PARAGRAPH자 이상, 메뉴·광고 문구 같은 짧은 줄은 뺀다)을 모은다. Arc XP 사이트(조선일보)는 화면을
 * 자바스크립트로 그려 HTML에 문단이 없으므로 페이지에 담긴 본문 JSON(Fusion.globalContent)의 글 요소를 읽는다.
 * 못 찾으면 빈 문자열이다.
 */
record ArticlePage(String imageUrl, String text, String description, String title) {

    ArticlePage(String imageUrl, String text) {
        this(imageUrl, text, null, null);
    }

    ArticlePage(String imageUrl, String text, String description) {
        this(imageUrl, text, description, null);
    }

    /** 사이트 이름 꼬리를 떼어 볼 구분자(제목 - 엑스포츠뉴스, 제목 | MK스포츠 …) */
    private static final String[] TITLE_SEPARATORS = {" - ", " | ", " :: ", " : ", " < ", " — "};

    /** 제목이 이 글자 수(한글·영문·숫자)보다 짧으면 앞부분이 같다는 것만으로는 바꾸지 않는다 */
    static final int MIN_PREFIX_LETTERS = 10;

    /**
     * 피드 제목이 문장부호를 빼거나 뒤를 자른 것이면(엑스포츠뉴스 사이트맵: 부호·한자를 빼고 앞부분만) 이 페이지의 제목
     * (og:title, 사이트 이름 꼬리를 뗀 것을 먼저)을 돌려준다. 한글·영문·숫자만 견줘 같거나, 피드 제목이 그 앞부분일 때만
     * 바꾼다(다른 기사·바뀐 제목이면 그대로). 바꿀 것이 없으면 null
     */
    String betterTitle(String stored) {
        if (title == null || stored == null) {
            return null;
        }
        String target = letters(stored);
        if (target.isEmpty()) {
            return null;
        }
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String separator : TITLE_SEPARATORS) {
            // 꼬리가 여러 겹이면(제목 < 해외야구 < 야구 < 기사본문 - 스포츠Q) 처음 나온 곳에서도 자른다
            int first = title.indexOf(separator);
            int last = title.lastIndexOf(separator);
            if (first > 0) {
                candidates.add(title.substring(0, first));
            }
            if (last > first) {
                candidates.add(title.substring(0, last));
            }
        }
        candidates.add(title);
        // 짧은 후보(본제목)부터 본다. 긴 후보에는 꼬리·경로가 붙어 있다
        java.util.List<String> cleaned = candidates.stream().map(FeedParser.Texts::title).filter(Objects::nonNull)
                .distinct().sorted(java.util.Comparator.comparingInt(String::length)).toList();
        if (cleaned.contains(stored)) {
            return null; // 이미 이 페이지의 제목이다
        }
        // 짧은 후보부터, 글자가 맞는 첫 후보에서 결정한다(더 긴 후보로 넘어가면 꼬리 붙은 제목을 고를 수 있다)
        for (String clean : cleaned) {
            String letters = letters(clean);
            if (letters.equals(target)) {
                // 글자가 같다: 부호·한자가 더 있을 때만 바꾼다(부호가 있는 피드 제목은 그대로)
                return nonSpace(clean) > nonSpace(stored) ? clean : null;
            }
            if (target.length() >= MIN_PREFIX_LETTERS && letters.startsWith(target)) {
                return clean; // 잘린 피드 제목의 뒤를 채운다
            }
        }
        return null;
    }

    /** 공백을 뺀 글자 수(부호·한자 포함). 빠진 것이 채워진 쪽이 더 많다 */
    private static long nonSpace(String text) {
        return text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
    }

    /** 견줄 글자: 한글·영문·숫자만(부호·공백·한자는 뺀다. 사이트맵이 한자까지 빼고 주기 때문) */
    private static String letters(String text) {
        return text.replaceAll("[^\\p{IsHangul}\\p{IsLatin}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    static final int MAX_TEXT = 8000;
    /** 기사 칸이 없는 사이트에서 본문으로 볼 문단의 최소 글자 수 */
    static final int LONG_PARAGRAPH = 60;
    /** 문단(p)으로 찾은 본문이 이보다 짧으면 줄바꿈으로 나뉜 긴 줄을 본문으로 본다 */
    static final int MIN_BODY_TEXT = 200;
    /** 줄바꿈으로 본문을 쓰는 사이트(엑스포츠뉴스 등)에서 본문으로 볼 줄의 최소 글자 수 */
    static final int LONG_LINE = 40;
    private static final Pattern NOISE = Pattern.compile("(?is)<(script|style|noscript|nav|footer)[\\s>].*?</\\1>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>|</p>|</div>|</li>|</h\\d>|</td>");
    /** 국내 언론사의 기사 본문 칸(엑스포츠뉴스 news_contents, 마이데일리 article_content, ndsoft article-view-content-div 등) */
    private static final Pattern BODY_BOX = Pattern.compile("(?i)<(div|section|article)\\b[^>]*(?:itemprop=[\"']articleBody[\"']"
            + "|id=[\"']article-view-content-div[\"']|class=[\"'][^\"']*\\b(?:news_contents|article_content|article_body|articleBody"
            + "|article-body|news_body|view_con|article_txt|news_view|art_body)\\b)[^>]*>");
    /** 구체적인 본문 칸이 없을 때만 쓰는 일반 article 칸 */
    private static final Pattern ARTICLE_OPEN = Pattern.compile("(?i)<(article)\\b[^>]*>");
    /** 로그용: 여는 태그의 이름 */
    private static final Pattern TAG_NAME = Pattern.compile("^<\\s*([a-zA-Z][a-zA-Z0-9]*)");
    /** 로그용: 첫 긴 문단을 담은 칸을 찾을 때 보는 여는 태그 */
    private static final Pattern CONTAINER_OPEN = Pattern.compile("(?i)<(?:div|section|article|main)\\b[^>]*>");
    /** 본문이 끝나는 줄: 저작권·무단 전재 안내 */
    private static final Pattern BODY_STOP = Pattern.compile("(?i)무단\\s?전재|무단\\s?복제|재배포\\s?금지|copyright|저작권자");
    /** 본문을 어느 정도 모은 뒤 나오면 끝으로 보는 줄: ▶ 관련 기사(본문 첫머리의 ▶ 요약 줄에서는 멈추지 않는다) */
    private static final Pattern RELATED = Pattern.compile("^▶");
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
    /**
     * Arc XP 기사 페이지가 본문을 담는 JSON(Fusion.globalContent={...};). 조선일보는 HTML에 본문 문단이 하나도 없고
     * 이 JSON의 content_elements 가운데 type이 text인 요소(문단 HTML)가 본문이다(11-12 확인, 이슈 #107)
     */
    private static final String ARC_CONTENT = "Fusion.globalContent=";
    /** 본문 JSON 뒤에 이어지는 스크립트(;Fusion.globalContentConfig=…)는 읽지 않는다 */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    static ArticlePage parse(byte[] html) {
        String page = new String(html, charset(html));
        // og:image를 먼저 보고, 없을 때만 twitter:image(트위터 카드에 사이트 로고를 두는 곳이 있다)
        String image = meta(page, "og:image", "og:image:secure_url");
        if (image == null) {
            image = meta(page, "twitter:image", "twitter:image:src");
        }
        return new ArticlePage(image, body(page),
                FeedParser.Texts.lead(meta(page, "og:description", "description")), meta(page, "og:title"));
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
                if (property.contains("image")) {
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
        String arc = arcBody(page);
        if (arc.length() >= MIN_BODY_TEXT) {
            return arc;
        }
        String text = paragraphBody(page);
        if (text.length() >= MIN_BODY_TEXT) {
            return text;
        }
        // 국내 언론사 다수는 본문을 p 없이 <br>로 나눠 쓴다: 칸·줄 경계로 나눈 긴 줄을 모은다
        String lines = longLines(page);
        String best = lines.length() > text.length() ? lines : text;
        if (best.length() >= MIN_BODY_TEXT) {
            return best;
        }
        // 그래도 못 찾았으면 본문이 첫 article 칸 밖에 있을 수 있다(연합뉴스, 이슈 #109). 위에서 찾은 사이트는 바뀌지 않는다
        String wider = widerParagraphs(page);
        return wider.length() > best.length() ? wider : best;
    }

    /**
     * 알려진 본문 칸(BODY_BOX)이 없을 때만: 모든 article 칸의 긴 문단, 모자라면 페이지 전체(스크립트·메뉴·꼬리 칸 뺀)의
     * 긴 문단을 저작권 안내 줄까지 모은다(그 줄이 있는 페이지만). 지금 코드는 article 칸이 있으면 첫 칸만 보는데, 연합뉴스는 article 칸이 2개고
     * 긴 문단 12개 가운데 본문이 그 밖에 있었다(운영 로그 11-12). 알려진 본문 칸이 있으면 짧아도 다른 곳을 긁지 않는다
     */
    private static String widerParagraphs(String page) {
        if (BODY_BOX.matcher(page).find()) {
            return "";
        }
        StringBuilder articles = new StringBuilder();
        Matcher article = ARTICLE.matcher(page);
        while (article.find()) {
            articles.append(article.group()).append('\n');
        }
        String inArticles = articles.isEmpty() ? "" : paragraphs(articles.toString(), LONG_PARAGRAPH);
        if (inArticles.length() >= MIN_BODY_TEXT) {
            return inArticles;
        }
        // 페이지 전체는 저작권 안내 줄(멈춤 줄)이 있을 때만 읽는다. 본문 끝을 알 수 없으면 관련 기사 소개·댓글 같은 긴 글을
        // 본문으로 읽을 수 있어서다(그럴 바엔 '본문 없음'으로 실패한다)
        String clean = NOISE.matcher(page).replaceAll(" ");
        if (!hasStopParagraph(clean)) {
            return inArticles;
        }
        String whole = paragraphs(clean, LONG_PARAGRAPH);
        return whole.length() > inArticles.length() ? whole : inArticles;
    }

    /** 저작권 안내 같은 멈춤 줄(BODY_STOP)이 문단(p)으로 있나 */
    private static boolean hasStopParagraph(String html) {
        Matcher p = PARAGRAPH.matcher(html);
        while (p.find()) {
            if (BODY_STOP.matcher(TAG.matcher(p.group()).replaceAll(" ")).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Arc XP 본문 JSON의 글 요소(type text)를 문단으로 읽는다. 사진·광고(raw_html) 요소는 뺀다. 문단 다듬기와 멈춤(저작권
     * 안내·▶ 관련 기사)은 HTML 문단과 같다. JSON이 없거나 읽을 수 없으면 빈 문자열
     */
    private static String arcBody(String page) {
        int at = page.indexOf(ARC_CONTENT);
        if (at < 0) {
            return "";
        }
        JsonNode content;
        try {
            content = JSON.readTree(page.substring(at + ARC_CONTENT.length()));
        } catch (JacksonException e) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        for (JsonNode element : content.path("content_elements")) {
            if ("text".equals(element.path("type").asString(""))) {
                html.append("<p>").append(element.path("content").asString("")).append("</p>\n");
            }
        }
        return paragraphs(html.toString(), 1);
    }

    /**
     * 본문을 못 찾았을 때 로그에 남길 페이지 모양. 기사 내용은 남기지 않는다: 크기, 문단(p) 수와 긴 문단 수(그 가운데 article
     * 칸 안), article 칸 수와 첫 칸 태그, 찾은 본문 칸(BODY_BOX), 첫 긴 문단의 순서와 바로 앞 칸 태그(본문이 어느 칸에
     * 있는지), 처음 멈춤 줄(저작권 안내)이 나온 문단 순서, 본문 JSON(Arc XP)이 있는지. 순서는 1부터, 없으면 -1
     */
    static String outline(byte[] html) {
        String page = new String(html, charset(html));
        String clean = NOISE.matcher(page).replaceAll(" ");
        int paragraphs = 0;
        int longParagraphs = 0;
        int firstLong = -1;
        int firstLongIndex = -1;
        int firstStop = -1;
        Matcher p = PARAGRAPH.matcher(clean);
        while (p.find()) {
            paragraphs++;
            String line = FeedParser.Texts.decode(TAG.matcher(p.group()).replaceAll(" ")).replaceAll("[\\s\\u00A0]+", " ").trim();
            if (firstStop < 0 && BODY_STOP.matcher(line).find()) {
                firstStop = paragraphs;
            }
            if (line.length() >= LONG_PARAGRAPH) {
                longParagraphs++;
                if (firstLong < 0) {
                    firstLong = p.start();
                    firstLongIndex = paragraphs;
                }
            }
        }
        int longInArticles = 0;
        Matcher block = ARTICLE.matcher(clean);
        while (block.find()) {
            Matcher q = PARAGRAPH.matcher(block.group());
            while (q.find()) {
                String line = FeedParser.Texts.decode(TAG.matcher(q.group()).replaceAll(" ")).replaceAll("[\\s\\u00A0]+", " ").trim();
                if (line.length() >= LONG_PARAGRAPH) {
                    longInArticles++;
                }
            }
        }
        Matcher box = BODY_BOX.matcher(clean);
        String found = box.find() ? openTag(box.group()) : "-";
        String around = "-";
        if (firstLong >= 0) {
            Matcher open = CONTAINER_OPEN.matcher(clean.substring(0, firstLong));
            while (open.find()) {
                around = openTag(open.group());
            }
        }
        int articles = 0;
        String firstArticle = "-";
        Matcher article = ARTICLE_OPEN.matcher(clean);
        while (article.find()) {
            if (articles++ == 0) {
                firstArticle = openTag(article.group());
            }
        }
        return String.format(Locale.ROOT, "%,d bytes, %d p (%d long, %d long in article), %d article (first %s), box %s, "
                        + "first long p #%d in %s, first stop p #%d, arc json %s",
                html.length, paragraphs, longParagraphs, longInArticles, articles, firstArticle, found, firstLongIndex, around,
                firstStop, page.contains(ARC_CONTENT) ? "yes" : "no");
    }

    /**
     * 로그용 여는 태그: 태그 이름과 id·class·itemprop 값만 남긴다(data-title·aria-label 같은 다른 속성에는 기사 제목이
     * 들어 있을 수 있다). 120자까지
     */
    private static String openTag(String tag) {
        Matcher name = TAG_NAME.matcher(tag);
        StringBuilder out = new StringBuilder("<").append(name.find() ? name.group(1).toLowerCase(Locale.ROOT) : "?");
        Matcher attr = ATTR.matcher(tag);
        while (attr.find()) {
            String key = attr.group(1).toLowerCase(Locale.ROOT);
            if (key.equals("id") || key.equals("class") || key.equals("itemprop")) {
                String value = attr.group(3) != null ? attr.group(3) : attr.group(4);
                out.append(' ').append(key).append("=\"").append(value.replaceAll("\\s+", " ").trim()).append('"');
            }
        }
        String one = out.append('>').toString();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    /**
     * 줄바꿈·칸 경계로 나눈 줄 중 LONG_LINE자 이상인 것. 기사 본문 칸(BODY_BOX)을 찾으면 거기서부터 보고(사이드바의 다른 기사
     * 제목이 섞이지 않게), 저작권 안내나 ▶ 관련 기사 줄에서 멈춘다. 스크립트·메뉴·꼬리 칸과 '사진=' 줄은 뺀다. MAX_TEXT자까지
     */
    private static String longLines(String page) {
        String clean = bodyBox(NOISE.matcher(page).replaceAll(" "));
        StringBuilder text = new StringBuilder();
        for (String raw : LINE_BREAK.split(clean)) {
            if (text.length() >= MAX_TEXT) {
                break;
            }
            String line = FeedParser.Texts.decode(TAG.matcher(raw).replaceAll(" ")).replaceAll("[\\s\\u00A0]+", " ").trim();
            if (line.isEmpty()) {
                continue;
            }
            if (BODY_STOP.matcher(line).find() || (text.length() >= MIN_BODY_TEXT && RELATED.matcher(line).find())) {
                break; // 본문이 끝났다(저작권·관련 기사)
            }
            if (line.length() >= LONG_LINE && !line.startsWith("사진=") && !RELATED.matcher(line).find()) {
                text.append(line).append('\n');
            }
        }
        String out = text.toString().trim();
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
    }

    /**
     * 국내 언론사 본문 칸(BODY_BOX, 없으면 첫 article 칸)의 안쪽만 돌려준다. 같은 이름 태그의 열고 닫힘을 세어 그 칸이 닫히는
     * 곳까지만 읽는다(사이드바·꼬리 칸이 섞이지 않게). 칸이 없으면 page 그대로(같은 객체)
     */
    private static String bodyBox(String page) {
        Matcher box = BODY_BOX.matcher(page);
        if (!box.find()) {
            box = ARTICLE_OPEN.matcher(page);
            if (!box.find()) {
                return page;
            }
        }
        String tag = box.group(1).toLowerCase(Locale.ROOT);
        Matcher tags = Pattern.compile("(?i)<(/?)" + tag + "\\b[^>]*>").matcher(page);
        int depth = 1;
        int from = box.end();
        while (tags.find(from)) {
            depth += tags.group(1).isEmpty() ? 1 : -1;
            if (depth == 0) {
                return page.substring(box.end(), tags.start());
            }
            from = tags.end();
        }
        return page.substring(box.end());
    }

    private static String paragraphBody(String page) {
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
        // 기사 칸이 없는 사이트: 국내 언론사 본문 칸 안만, 없으면 article 칸들(없으면 페이지 전체)의 긴 문단만
        String box = bodyBox(page);
        if (box != page) {
            return paragraphs(box, LONG_PARAGRAPH);
        }
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
            if (BODY_STOP.matcher(line).find() || (text.length() >= MIN_BODY_TEXT && RELATED.matcher(line).find())) {
                break; // 본문이 끝났다(저작권·관련 기사)
            }
            if (line.length() >= minLength && !RELATED.matcher(line).find()) {
                text.append(line).append('\n');
            }
        }
        String out = text.toString().trim();
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
    }
}
