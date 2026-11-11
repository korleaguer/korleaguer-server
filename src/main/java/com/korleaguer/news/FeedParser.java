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
 * RSS 2.0·Atom 피드에서 기사마다 제목·링크·발행 시각만 읽는다. 본문(description·content)·사진은 읽지 않는다.
 * 외부 엔티티·DTD는 처리하지 않는다(XXE 방지).
 */
public final class FeedParser {

    static final int MAX_TITLE = 300;
    static final int MAX_LINK = 1000;

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String DUBLIN_CORE = "http://purl.org/dc/elements/1.1/";

    private FeedParser() {
    }

    /**
     * 기사 하나. publishedAt은 피드에 없거나 읽을 수 없으면 null이다.
     */
    public record Item(String title, String link, Instant publishedAt) {
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
                        } else if (name.equals("link") && plainOrAtom(ns)) {
                            String href = reader.getAttributeValue(null, "href");
                            if (href != null) {
                                String rel = reader.getAttributeValue(null, "rel");
                                if ((rel == null || rel.equals("alternate")) && draft.link == null) {
                                    draft.link = href;
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
        return (name.equals("item") && !ATOM.equals(ns)) || (name.equals("entry") && ATOM.equals(ns));
    }

    private static boolean plainOrAtom(String ns) {
        return ns == null || ns.isEmpty() || ATOM.equals(ns);
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
            return new Item(cleanTitle, cleanLink, published);
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
