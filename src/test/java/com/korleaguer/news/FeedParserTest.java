package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 피드 읽기: 기사마다 제목·링크·발행 시각만, 본문·사진 칸은 읽지 않는다. (아래 기사 내용은 지어낸 값) */
class FeedParserTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void rssItemKeepsOnlyTitleLinkAndDate() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                     xmlns:media="http://search.yahoo.com/mrss/">
                  <channel>
                    <title>스포츠</title>
                    <link>https://news.example.kr</link>
                    <item>
                      <title><![CDATA[노시환, 협상 시작 &quot;좋은 소식 기다린다&quot;]]></title>
                      <link>
                        https://news.example.kr/view/1001
                      </link>
                      <description><![CDATA[<p>본문 전체가 여기 있다</p>]]></description>
                      <content:encoded><![CDATA[<p>본문</p>]]></content:encoded>
                      <media:content url="https://img.example.kr/1.jpg"><media:title>사진 설명</media:title></media:content>
                      <pubDate>Wed, 11 Nov 2026 11:10:23 +0900</pubDate>
                    </item>
                    <item><title>링크 없는 기사</title><pubDate>Wed, 11 Nov 2026 11:00:00 +0900</pubDate></item>
                    <item><title>이상한 링크</title><link>javascript:alert(1)</link></item>
                  </channel>
                </rss>
                """;

        List<FeedParser.Item> items = FeedParser.parse(bytes(xml), SEOUL);

        assertThat(items).containsExactly(new FeedParser.Item("노시환, 협상 시작 \"좋은 소식 기다린다\"",
                "https://news.example.kr/view/1001", Instant.parse("2026-11-11T02:10:23Z")));
    }

    @Test
    void atomEntryUsesAlternateLinkAndPublished() {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>예시</title>
                  <entry>
                    <title type="html">Padres Interested In Si-Hwan Roh</title>
                    <link rel="replies" href="https://example.com/2026/11/roh/comments"/>
                    <link rel="alternate" href="https://example.com/2026/11/roh.html"/>
                    <updated>2026-11-10T20:00:00Z</updated>
                    <published>2026-11-10T19:00:00-05:00</published>
                  </entry>
                  <entry>
                    <title>Updated only</title>
                    <link href="https://example.com/2026/11/other.html"/>
                    <updated>2026-11-09T12:00:00Z</updated>
                  </entry>
                </feed>
                """;

        List<FeedParser.Item> items = FeedParser.parse(bytes(xml), SEOUL);

        assertThat(items).containsExactly(
                new FeedParser.Item("Padres Interested In Si-Hwan Roh", "https://example.com/2026/11/roh.html",
                        Instant.parse("2026-11-11T00:00:00Z")),
                new FeedParser.Item("Updated only", "https://example.com/2026/11/other.html",
                        Instant.parse("2026-11-09T12:00:00Z")));
    }

    @Test
    void dateWithoutZoneUsesTheSourceZone() {
        String xml = """
                <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel>
                  <item><title>시간대 없는 날짜</title><link>https://news.example.kr/2</link>
                    <dc:date>2026-11-11 11:27:26</dc:date></item>
                  <item><title>날짜 없음</title><link>https://news.example.kr/3</link></item>
                </channel></rss>
                """;

        List<FeedParser.Item> items = FeedParser.parse(bytes(xml), SEOUL);

        assertThat(items).extracting(FeedParser.Item::publishedAt)
                .containsExactly(Instant.parse("2026-11-11T02:27:26Z"), null);
    }

    @Test
    void readsCommonDateShapes() {
        assertThat(FeedParser.FeedDates.parse("Sun, 01 Nov 2026 21:40:49 +0000", SEOUL))
                .isEqualTo(Instant.parse("2026-11-01T21:40:49Z"));
        assertThat(FeedParser.FeedDates.parse("Wed, 11 Nov 2026 11:10:23 GMT", SEOUL))
                .isEqualTo(Instant.parse("2026-11-11T11:10:23Z"));
        assertThat(FeedParser.FeedDates.parse("11 Nov 2026 11:10 +09:00", SEOUL))
                .isEqualTo(Instant.parse("2026-11-11T02:10:00Z"));
        assertThat(FeedParser.FeedDates.parse("2026-11-11T11:10:23+09:00", SEOUL))
                .isEqualTo(Instant.parse("2026-11-11T02:10:23Z"));
        assertThat(FeedParser.FeedDates.parse("2026.11.11 11:16", SEOUL))
                .isEqualTo(Instant.parse("2026-11-11T02:16:00Z"));
        assertThat(FeedParser.FeedDates.parse("어제", SEOUL)).isNull();
    }

    @Test
    void titleLosesTagsAndEntitiesAndIsCapped() {
        assertThat(FeedParser.Texts.title("<b>굵은</b>&nbsp;제목 &#39;따옴표&#39; &amp; 끝")).isEqualTo("굵은 제목 '따옴표' & 끝");
        assertThat(FeedParser.Texts.title("  ")).isNull();
        assertThat(FeedParser.Texts.title("가".repeat(400))).hasSize(FeedParser.MAX_TITLE);
    }

    @Test
    void externalEntitiesAreNeverResolved() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss version="2.0"><channel><item><title>&xxe;</title><link>https://x.example/1</link></item></channel></rss>
                """;

        List<FeedParser.Item> items;
        try {
            items = FeedParser.parse(bytes(xml), SEOUL);
        } catch (IllegalArgumentException rejected) {
            return; // 읽기를 거절한 것도 안전하다
        }
        assertThat(items).noneMatch(item -> item.title().contains("root"));
    }

    @Test
    void notXmlIsRejected() {
        assertThatThrownBy(() -> FeedParser.parse(bytes("<html><body>차단됨"), SEOUL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
