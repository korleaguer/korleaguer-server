package com.korleaguer.client;

import com.korleaguer.news.HttpGetter;
import com.korleaguer.news.PoliteFetcher;
import com.korleaguer.video.PlayVideoFinder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Savant 영상 페이지에서 mp4 주소를 찾는다(네트워크 없음, 가짜 HTTP) */
class SavantClipsTest {

    private static final String PLAY = "8903db8e-566a-3ac1-9121-44c3dffe23c6";
    private static final String MP4 = "https://sporty-clips.mlb.com/NnlNNW9fWGw0TUFRPT1fRGdoUlZ3QldYbGNB.mp4";

    private final List<String> asked = new ArrayList<>();

    @Test
    void findsTheClipOnTheVideoPage() {
        SavantClips clips = clips("<html><video><source src=\"" + MP4 + "\" type=\"video/mp4\"></video></html>");

        PlayVideoFinder.Lookup lookup = clips.find(PLAY);

        assertThat(lookup.answered()).isTrue();
        assertThat(lookup.mp4Url()).contains(MP4);
        assertThat(asked).containsExactly("https://baseballsavant.mlb.com/robots.txt",
                SavantClips.PAGE + PLAY);
    }

    @Test
    void clipAddressWrittenWithHtmlEntitiesIsUnescaped() {
        // 운영에서 본 모양(2026-11-15, 이정후 9-18 3회 공): 끝의 '='가 HTML 표기로 온다
        String page = "<source src=\"https://sporty-clips.mlb.com/OTdQNGxfV0ZRVkV3dEdEUT09X0JRZ0VYRkVIQXdRQUFBRldVUUFIQ1ZKVUFGa0ZWQVVBQlZNTVV3VlFVRmRRQVFaVA&#x3D;&#x3D;.mp4\">";

        assertThat(SavantClips.mp4In(page)).contains(
                "https://sporty-clips.mlb.com/OTdQNGxfV0ZRVkV3dEdEUT09X0JRZ0VYRkVIQXdRQUFBRldVUUFIQ1ZKVUFGa0ZWQVVBQlZNTVV3VlFVRmRRQVFaVA==.mp4");
        assertThat(SavantClips.unescape("a&#61;b&amp;c")).isEqualTo("a=b&c");
    }

    @Test
    void pageWithoutVideoIsAnsweredEmpty() {
        SavantClipsTest.this.asked.clear();
        PlayVideoFinder.Lookup lookup = clips("<html><title>Baseball Savant Videos</title></html>").find(PLAY);

        assertThat(lookup.answered()).isTrue();
        assertThat(lookup.mp4Url()).isEmpty();
    }

    @Test
    void doesNotAskForSomethingThatIsNotAPlayId() {
        PlayVideoFinder.Lookup lookup = clips("").find("../../admin");

        assertThat(lookup.mp4Url()).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void failedPageIsNotAnswered() {
        HttpGetter http = (uri, headers) -> {
            asked.add(uri.toString());
            return uri.getPath().equals("/robots.txt")
                    ? new HttpGetter.Reply(200, Map.of(), "User-agent: *\nDisallow:\n".getBytes(StandardCharsets.UTF_8))
                    : new HttpGetter.Reply(500, Map.of(), new byte[0]);
        };
        SavantClips clips = new SavantClips(new PoliteFetcher(http, List.of("korleaguer"), clock(), d -> { }));

        assertThat(clips.find(PLAY).answered()).isFalse();
    }

    private SavantClips clips(String page) {
        HttpGetter http = (uri, headers) -> {
            asked.add(uri.toString());
            String body = uri.getPath().equals("/robots.txt") ? "User-agent: *\nDisallow:\n" : page;
            return new HttpGetter.Reply(200, Map.of("content-type", "text/html"), body.getBytes(StandardCharsets.UTF_8));
        };
        return new SavantClips(new PoliteFetcher(http, List.of("korleaguer"), clock(), d -> { }));
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
    }
}
