package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 피드 읽기: 기사마다 제목·링크·발행 시각, 글자만 뗀 첫머리, 사진 주소(파일은 받지 않음). (아래 기사 내용은 지어낸 값) */
class FeedParserTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void rssItemKeepsTitleLinkDateAndLead() {
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
                "https://news.example.kr/view/1001", Instant.parse("2026-11-11T02:10:23Z"), "본문 전체가 여기 있다",
                "https://img.example.kr/1.jpg"));
    }

    @Test
    void picturesComeFromEnclosureMediaOrTheFirstImgAndBecomeHttps() {
        String xml = """
                <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>t</title>
                  <item><title>가</title><link>https://n.example.kr/1</link><enclosure url="https://img.example.kr/1.jpg" type="image/jpeg"/></item>
                  <item><title>나</title><link>https://n.example.kr/2</link><media:content url="http://img.example.kr/2.png" medium="image"/></item>
                  <item><title>다</title><link>https://n.example.kr/3</link><media:thumbnail url="//img.example.kr/3.webp"/></item>
                  <item><title>라</title><link>https://n.example.kr/4</link><description><![CDATA[<p><img alt="x" src="https://img.example.kr/4.jpg?w=600"></p>]]></description></item>
                  <item><title>마</title><link>https://n.example.kr/5</link><enclosure url="https://n.example.kr/5.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """;

        assertThat(FeedParser.parse(bytes(xml), SEOUL)).extracting(FeedParser.Item::imageUrl).containsExactly(
                "https://img.example.kr/1.jpg", "https://img.example.kr/2.png", "https://img.example.kr/3.webp",
                "https://img.example.kr/4.jpg?w=600", null);
    }

    @Test
    void aNewsSitemapIsReadLikeAFeed() {
        String xml = """
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:news="http://www.google.com/schemas/sitemap-news/0.9"
                        xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">
                  <url>
                    <loc>https://n.example.kr/view/1</loc>
                    <news:news><news:publication><news:name>예시</news:name><news:language>ko</news:language></news:publication>
                      <news:publication_date>2026-11-11T09:00:00+09:00</news:publication_date><news:title>노시환, 협상 시작</news:title></news:news>
                    <image:image><image:loc>https://img.example.kr/1.jpg</image:loc></image:image>
                  </url>
                  <url><loc>https://n.example.kr/view/2</loc><lastmod>2026-11-10T10:00:00+09:00</lastmod>
                    <news:news><news:title>제목만 있는 기사</news:title></news:news></url>
                </urlset>
                """;

        assertThat(FeedParser.parse(bytes(xml), SEOUL)).containsExactly(
                new FeedParser.Item("노시환, 협상 시작", "https://n.example.kr/view/1", Instant.parse("2026-11-11T00:00:00Z"), null,
                        "https://img.example.kr/1.jpg"),
                new FeedParser.Item("제목만 있는 기사", "https://n.example.kr/view/2", Instant.parse("2026-11-10T01:00:00Z"), null, null));
    }

    @Test
    void leadComesFromTheBodyWithoutPhotosOrByline() {
        // 마이데일리 꼴: description 없이 content:encoded에 사진과 본문 전체
        String xml = """
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>t</title>
                  <item>
                    <title>노시환 소식</title><link>https://news.example.kr/view/7</link>
                    <content:encoded><![CDATA[<img src="https://img.example.kr/a.jpg" alt="사진 설명 /게티이미지코리아"/><p>[예시일보 = 홍길동 기자] “메이저리그에서 뛰고 싶다.”</p><p> </p><p>노시환이 포스팅 협상을 시작했다. 30개 구단과 12월 1일까지 이야기할 수 있다. 미국 언론은 노시환을 3루수 보강 후보로 꼽는다. 한화는 이미 장기 계약을 맺어 두었다. 그래서 계약 규모가 관심을 모은다. 노시환은 2019년에 데뷔했고 올해 홈런 서른 개를 넘겼다. 수비에서도 꾸준했다.</p>]]></content:encoded>
                  </item>
                </channel></rss>
                """;

        String lead = FeedParser.parse(bytes(xml), SEOUL).getFirst().lead();

        assertThat(lead).startsWith("“메이저리그에서 뛰고 싶다.” 노시환이 포스팅 협상을 시작했다.")
                .doesNotContain("홍길동", "img", "게티이미지")
                .endsWith(".");
        assertThat(lead.length()).isBetween(100, FeedParser.Texts.MAX_LEAD);
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
