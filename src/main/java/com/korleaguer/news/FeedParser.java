package com.korleaguer.news;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSS 2.0·Atom 피드와 뉴스 사이트맵(검색엔진용 공개 목록, RSS가 없는 언론사)에서 기사마다 제목·링크·발행 시각,
 * 첫머리(lead), 대표 사진 주소(imageUrl)를 읽는다. 첫머리는 소개 글(description·summary)이나 본문(content:encoded)의
 * 앞 문단에서 글자만 200자 안팎 떼어 낸 것이다(사이트맵에는 없다). 사진은 enclosure·media:content·media:thumbnail·
 * 사이트맵 image:loc·본문 첫 img의 주소만 쓴다(파일은 받지 않는다). 나머지 본문은 버린다.
 * 첫머리를 화면에 쓸지는 출처마다 정한다(NewsProperties.Source.preview). 외부 엔티티·DTD는 처리하지 않는다(XXE 방지).
 */
public final class FeedParser {

    static final int MAX_TITLE = 300;
    static final int MAX_LINK = 1000;

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String DUBLIN_CORE = "http://purl.org/dc/elements/1.1/";
    private static final String CONTENT = "http://purl.org/rss/1.0/modules/content/";
    private static final String MEDIA = "http://search.yahoo.com/mrss/";
    private static final String SITEMAP = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String SITEMAP_NEWS = "http://www.google.com/schemas/sitemap-news/0.9";
    private static final String SITEMAP_IMAGE = "http://www.google.com/schemas/sitemap-image/1.1";

    private FeedParser() {
    }

    /**
     * 기사 하나. publishedAt은 피드에 없거나 읽을 수 없으면 null이다.
     */
    public record Item(String title, String link, Instant publishedAt, String lead, String imageUrl) {

        public Item(String title, String link, Instant publishedAt) {
            this(title, link, publishedAt, null, null);
        }

        public Item(String title, String link, Instant publishedAt, String lead) {
            this(title, link, publishedAt, lead, null);
        }
    }

    /**
     * @param zone 발행 시각에 시간대 표시가 없을 때 쓸 시간대
     * @throws IllegalArgumentException XML로 읽을 수 없을 때
     */
    public static List<Item> parse(byte[] xml, ZoneId zone) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        List<Item> items = new ArrayList<>();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                Draft draft = null;
                int itemDepth = 0;
                int depth = 0;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        String name = reader.getLocalName();
                        String ns = reader.getNamespaceURI();
                        if (draft == null) {
                            if (isItem(name, ns)) {
                                draft = new Draft();
                                itemDepth = depth;
                            }
                            continue;
                        }
                        // 기사 바로 아래 칸만 본다(media:content 안의 title 같은 것은 건너뛴다)
                        if (depth != itemDepth + 1) {
                            continue;
                        }
                        if (name.equals("title") && plainOrAtom(ns) && draft.title == null) {
                            draft.title = text(reader);
                            depth--;
                        } else if (name.equals("loc") && SITEMAP.equals(ns) && draft.link == null) {
                            draft.link = text(reader);
                            depth--;
                        } else if (name.equals("lastmod") && SITEMAP.equals(ns) && draft.updated == null) {
                            draft.updated = text(reader);
                            depth--;
                        } else if (name.equals("news") && SITEMAP_NEWS.equals(ns)) {
                            readSitemapNews(reader, draft);
                            depth--;
                        } else if (name.equals("image") && SITEMAP_IMAGE.equals(ns)) {
                            readSitemapImage(reader, draft);
                            depth--;
                        } else if (name.equals("enclosure") && (ns == null || ns.isEmpty())) {
                            draft.offerImage(reader.getAttributeValue(null, "url"), reader.getAttributeValue(null, "type"));
                        } else if ((name.equals("content") || name.equals("thumbnail")) && MEDIA.equals(ns)) {
                            String medium = reader.getAttributeValue(null, "medium");
                            String type = reader.getAttributeValue(null, "type");
                            draft.offerImage(reader.getAttributeValue(null, "url"),
                                    name.equals("thumbnail") || "image".equals(medium) ? "image/" : type);
                        } else if (name.equals("link") && plainOrAtom(ns)) {
                            String href = reader.getAttributeValue(null, "href");
                            if (href != null) {
                                String rel = reader.getAttributeValue(null, "rel");
                                if ((rel == null || rel.equals("alternate")) && draft.link == null) {
                                    draft.link = href;
                                } else if ("enclosure".equals(rel)) {
                                    draft.offerImage(href, reader.getAttributeValue(null, "type"));
                                }
                            } else if (draft.link == null) {
                                draft.link = text(reader);
                                depth--;
                            }
                        } else if (isDate(name, ns) && draft.date == null) {
                            draft.date = text(reader);
                            depth--;
                        } else if (name.equals("updated") && ATOM.equals(ns) && draft.updated == null) {
                            draft.updated = text(reader);
                            depth--;
                        } else if (isSummary(name, ns) && draft.summary == null) {
                            draft.summary = text(reader);
                            depth--;
                        } else if (name.equals("encoded") && CONTENT.equals(ns) && draft.content == null) {
                            draft.content = text(reader);
                            depth--;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (draft != null && depth == itemDepth) {
                            Item item = draft.toItem(zone);
                            if (item != null) {
                                items.add(item);
                            }
                            draft = null;
                        }
                        depth--;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("not a readable RSS/Atom feed: " + e.getMessage(), e);
        }
        return items;
    }

    private static boolean isItem(String name, String ns) {
        return (name.equals("item") && !ATOM.equals(ns)) || (name.equals("entry") && ATOM.equals(ns))
                || (name.equals("url") && SITEMAP.equals(ns));
    }

    /** 사이트맵 news:news 칸에서 제목과 발행 시각을 읽는다. 끝나면 이 칸의 END_ELEMENT에 서 있다 */
    private static void readSitemapNews(XMLStreamReader reader, Draft draft) throws XMLStreamException {
        int nested = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                if (name.equals("title") && draft.title == null) {
                    draft.title = text(reader);
                } else if (name.equals("publication_date") && draft.date == null) {
                    draft.date = text(reader);
                } else {
                    nested++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (nested == 0) {
                    return;
                }
                nested--;
            }
        }
    }

    /** 사이트맵 image:image 칸에서 첫 사진 주소를 읽는다. 끝나면 이 칸의 END_ELEMENT에 서 있다 */
    private static void readSitemapImage(XMLStreamReader reader, Draft draft) throws XMLStreamException {
        int nested = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (reader.getLocalName().equals("loc")) {
                    draft.offerImage(text(reader), "image/");
                } else {
                    nested++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (nested == 0) {
                    return;
                }
                nested--;
            }
        }
    }

    private static boolean plainOrAtom(String ns) {
        return ns == null || ns.isEmpty() || ATOM.equals(ns);
    }

    private static boolean isSummary(String name, String ns) {
        return (name.equals("description") && (ns == null || ns.isEmpty()))
                || (name.equals("summary") && ATOM.equals(ns));
    }

    private static boolean isDate(String name, String ns) {
        return (name.equals("pubDate") && (ns == null || ns.isEmpty()))
                || (name.equals("published") && ATOM.equals(ns))
                || (name.equals("date") && DUBLIN_CORE.equals(ns));
    }

    /** 이 칸의 글자만 모은다(안에 다른 칸이 있으면 그 글자도 붙인다). 끝나면 이 칸의 END_ELEMENT에 서 있다. */
    private static String text(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int nested = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        text.append(reader.getText());
                case XMLStreamConstants.START_ELEMENT -> nested++;
                case XMLStreamConstants.END_ELEMENT -> {
                    if (nested == 0) {
                        return text.toString();
                    }
                    nested--;
                }
                default -> {
                    // 주석 등은 건너뛴다
                }
            }
        }
        return text.toString();
    }

    private static final class Draft {
        String title;
        String link;
        String date;
        String updated;
        String summary;
        String content;
        String image;

        /** 사진으로 보이는 첫 주소만 받는다(type이 image/로 시작하거나, type이 없으면 주소가 사진 확장자) */
        void offerImage(String url, String type) {
            if (image != null || url == null || url.isBlank()) {
                return;
            }
            boolean pictureType = type != null && type.toLowerCase(Locale.ROOT).startsWith("image/");
            if (pictureType || (type == null && Texts.looksLikePicture(url))) {
                image = url;
            }
        }

        Item toItem(ZoneId zone) {
            String cleanTitle = Texts.title(title);
            String cleanLink = link == null ? null : link.trim();
            if (cleanTitle == null || cleanLink == null || !isWebLink(cleanLink) || cleanLink.length() > MAX_LINK) {
                return null;
            }
            Instant published = FeedDates.parse(date, zone);
            if (published == null) {
                published = FeedDates.parse(updated, zone);
            }
            String lead = Texts.lead(summary);
            String picture = image != null ? image : Texts.firstImage(summary);
            if (picture == null) {
                picture = Texts.firstImage(content);
            }
            return new Item(cleanTitle, cleanLink, published, lead != null ? lead : Texts.lead(content),
                    Texts.imageUrl(picture));
        }

        private static boolean isWebLink(String link) {
            String lower = link.toLowerCase(Locale.ROOT);
            return lower.startsWith("https://") || lower.startsWith("http://");
        }
    }

    /** 제목 다듬기: HTML 태그를 빼고 HTML 엔티티를 풀고 공백을 하나로. 비면 null */
    static final class Texts {

        private static final Pattern TAG = Pattern.compile("<[^>]{0,200}>");
        private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");
        private static final Map<String, String> NAMED = Map.ofEntries(
                Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
                Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("middot", "·"), Map.entry("hellip", "…"),
                Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
                Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("bull", "•"));

        private Texts() {
        }

        static String title(String raw) {
            if (raw == null) {
                return null;
            }
            String text = decode(TAG.matcher(raw).replaceAll(" "));
            text = text.replaceAll("[\\s\\u00A0]+", " ").trim();
            if (text.isEmpty()) {
                return null;
            }
            return text.length() > MAX_TITLE ? text.substring(0, MAX_TITLE - 1) + "…" : text;
        }

        static final int MAX_LEAD = 200;
        static final int MAX_IMAGE_URL = 1000;
        private static final Pattern IMG_SRC = Pattern.compile("(?is)<img\\s[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"']");
        private static final Pattern PICTURE = Pattern.compile("(?i)\\.(jpe?g|png|webp|gif|avif)(\\?.*)?$");

        static boolean looksLikePicture(String url) {
            return PICTURE.matcher(url.trim()).find();
        }

        /** HTML 조각의 첫 img 주소. 없으면 null */
        static String firstImage(String html) {
            if (html == null) {
                return null;
            }
            Matcher m = IMG_SRC.matcher(html);
            return m.find() ? m.group(1) : null;
        }

        /**
         * 화면에 쓸 사진 주소로 다듬는다: 엔티티 풀기, //로 시작하면 https:, http는 https로 올린다(https 사이트에서 http 사진은
         * 브라우저가 막는다. https가 안 되는 서버면 화면에서 안 뜨고 대신 선수 사진이 나온다). 쓸 수 없으면 null
         */
        static String imageUrl(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String url = decode(raw.trim());
            if (url.startsWith("//")) {
                url = "https:" + url;
            } else if (url.regionMatches(true, 0, "http://", 0, 7)) {
                url = "https://" + url.substring(7);
            }
            if (!url.regionMatches(true, 0, "https://", 0, 8) || url.length() > MAX_IMAGE_URL || url.contains(" ")) {
                return null;
            }
            return url;
        }
        private static final Pattern BLOCK_END = Pattern.compile("(?i)</p>|<br\\s*/?>|</div>|</h\\d>");
        private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]*>");
        // 첫 문단 앞의 '[마이데일리 = 김진성 기자]' 같은 기자 표시
        private static final Pattern BYLINE = Pattern.compile("^\\[[^\\]]{1,40}]\\s*");

        /**
         * 기사 첫머리: 태그·사진을 빼고 문단을 이어 붙여 앞에서 200자 안팎(문장이 끝나는 곳에서 자른다). 비면 null.
         * 맨 앞 기자 표시([언론사 = 기자])는 뺀다(언론사는 화면에 따로 보인다).
         */
        static String lead(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String text = ANY_TAG.matcher(BLOCK_END.matcher(raw).replaceAll("\n")).replaceAll(" ");
            StringBuilder joined = new StringBuilder();
            for (String paragraph : decode(text).split("\n")) {
                String p = paragraph.replaceAll("[\\s\\u00A0]+", " ").trim();
                if (joined.isEmpty()) {
                    p = BYLINE.matcher(p).replaceFirst("");
                }
                if (p.isEmpty()) {
                    continue;
                }
                if (!joined.isEmpty()) {
                    joined.append(' ');
                }
                joined.append(p);
                if (joined.length() >= MAX_LEAD) {
                    break;
                }
            }
            String lead = joined.toString().trim();
            if (lead.isEmpty()) {
                return null;
            }
            if (lead.length() <= MAX_LEAD) {
                return lead;
            }
            String head = lead.substring(0, MAX_LEAD);
            int end = Math.max(head.lastIndexOf(". "), head.lastIndexOf("” "));
            return end >= MAX_LEAD / 2 ? head.substring(0, end + 1).trim() : head.trim() + "…";
        }

        static String decode(String text) {
            Matcher m = ENTITY.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(entity(m.group(1), m.group())));
            }
            m.appendTail(out);
            return out.toString();
        }

        private static String entity(String body, String original) {
            try {
                if (body.startsWith("#x")) {
                    return new String(Character.toChars(Integer.parseInt(body.substring(2), 16)));
                }
                if (body.startsWith("#")) {
                    return new String(Character.toChars(Integer.parseInt(body.substring(1))));
                }
            } catch (IllegalArgumentException e) {
                return original;
            }
            return NAMED.getOrDefault(body, original);
        }
    }

    /** 피드의 발행 시각 읽기. RFC 822/1123(RSS), ISO 8601(Atom), 시간대 없는 국내 형식(그 출처의 시간대로) */
    static final class FeedDates {

        private static final List<DateTimeFormatter> ZONED = List.of(
                DateTimeFormatter.RFC_1123_DATE_TIME,
                rfc822("XXX"),   // +09:00
                rfc822("z"));    // KST, EST, GMT
        private static final List<DateTimeFormatter> LOCAL = List.of(
                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]"),
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm[:ss]"),
                DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm[:ss]"),
                DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm[:ss]"));

        private FeedDates() {
        }

        static Instant parse(String text, ZoneId zone) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String t = text.trim().replaceAll("\\s+", " ");
            for (DateTimeFormatter f : ZONED) {
                try {
                    return ZonedDateTime.parse(t, f).toInstant();
                } catch (DateTimeParseException ignored) {
                    // 다음 형식
                }
            }
            try {
                return OffsetDateTime.parse(t).toInstant();
            } catch (DateTimeParseException ignored) {
                // 다음 형식
            }
            for (DateTimeFormatter f : LOCAL) {
                try {
                    return LocalDateTime.parse(t, f).atZone(zone).toInstant();
                } catch (DateTimeParseException ignored) {
                    // 다음 형식
                }
            }
            return null;
        }

        private static DateTimeFormatter rfc822(String zonePattern) {
            return new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .optionalStart().appendPattern("EEE, ").optionalEnd()
                    .appendPattern("d MMM uuuu HH:mm[:ss] " + zonePattern)
                    .toFormatter(Locale.ENGLISH);
        }
    }
}
