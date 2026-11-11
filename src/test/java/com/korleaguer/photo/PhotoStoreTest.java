package com.korleaguer.photo;

import com.korleaguer.config.DataMode;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Photo;
import com.korleaguer.domain.PhotoRepository;
import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.news.HttpGetter;
import com.korleaguer.news.PoliteFetcher;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 사진 호스팅: 처음 한 번만 받고, 7일마다 바뀌었는지만 묻고(같으면 다시 쓰지 않음), 목록에 없는 사진은 받지 않는다. */
class PhotoStoreTest {

    private static final String MLB = "https://img.example.com/people/808982/headshot";
    private static final String KBO = "https://img.example.kr/roh.webp";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T01:00:00Z"));
    private final Map<String, Deque<HttpGetter.Reply>> replies = new HashMap<>();
    private final List<String> requested = new ArrayList<>();
    private final List<Map<String, String>> sentHeaders = new ArrayList<>();
    private final PhotoRepository repository = mock(PhotoRepository.class);
    private final Map<String, Photo> saved = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<String>getArgument(0))));
        when(repository.save(any(Photo.class))).thenAnswer(inv -> {
            Photo photo = inv.getArgument(0);
            saved.put(photo.getPhotoKey(), photo);
            return photo;
        });
        reply("https://img.example.com/robots.txt", 404, "", Map.of());
        reply("https://img.example.kr/robots.txt", 403, "", Map.of()); // 그림 말고는 다 막는 사진 서버
    }

    @Test
    void firstLoadFetchesOnceThenServesTheStoredCopy() {
        reply(MLB, 200, "face", Map.of("content-type", "image/webp", "etag", "\"v1\""));
        PhotoStore store = store(DataMode.MLB);

        Optional<Photo> first = store.load("mlb-808982");
        Optional<Photo> second = store.load("mlb-808982");

        assertThat(first).map(Photo::getContentType).contains("image/webp");
        assertThat(second).map(p -> new String(p.getData(), StandardCharsets.UTF_8)).contains("face");
        assertThat(requested.stream().filter(MLB::equals)).hasSize(1);
        assertThat(sentHeaders.get(requested.indexOf(MLB))).containsKey("Accept");
    }

    @Test
    void robotsForbiddenImageServerStillServesTheChosenPhoto() {
        reply(KBO, 200, "roh", Map.of("content-type", "image/webp"));

        assertThat(store(DataMode.MLB).load("kbo-si-hwan-roh")).isPresent();
    }

    @Test
    void unknownKeysAreNeverFetched() {
        assertThat(store(DataMode.MLB).load("mlb-1")).isEmpty();
        assertThat(requested).isEmpty();
    }

    @Test
    void sampleModeNeverFetches() {
        reply(MLB, 200, "face", Map.of("content-type", "image/webp"));
        PhotoStore store = store(DataMode.SAMPLE);

        assertThat(store.load("mlb-808982")).isEmpty();
        assertThat(store.refresh()).isZero();
        assertThat(requested).isEmpty();
    }

    @Test
    void recheckAfterSevenDaysUsesConditionalHeadersAndKeepsTheCopyWhenUnchanged() {
        reply(MLB, 200, "face", Map.of("content-type", "image/webp", "etag", "\"v1\"",
                "last-modified", "Wed, 11 Nov 2026 01:00:00 GMT"));
        reply(KBO, 200, "roh", Map.of("content-type", "image/webp"));
        PhotoStore store = store(DataMode.MLB);
        assertThat(store.refresh()).isEqualTo(2);
        int before = requested.size();
        Instant kboFetched = saved.get("kbo-si-hwan-roh").getFetchedAt();

        clock.advance(Duration.ofDays(3));
        assertThat(store.refresh()).isZero();
        assertThat(requested).hasSize(before); // 7일 전에는 묻지도 않는다

        reply(MLB, 304, "", Map.of());
        reply(KBO, 200, "roh", Map.of("content-type", "image/webp")); // 확인값이 없는 서버: 받아도 같으면 다시 쓰지 않는다
        clock.advance(Duration.ofDays(5));
        Instant askedFrom = clock.instant();
        assertThat(store.refresh()).isZero();

        int mlbAsk = requested.lastIndexOf(MLB);
        assertThat(sentHeaders.get(mlbAsk))
                .containsEntry("If-None-Match", "\"v1\"")
                .containsEntry("If-Modified-Since", "Wed, 11 Nov 2026 01:00:00 GMT");
        assertThat(saved.get("mlb-808982").getCheckedAt()).isAfterOrEqualTo(askedFrom); // 물어본 시각으로 바뀜
        assertThat(saved.get("kbo-si-hwan-roh").getFetchedAt()).isEqualTo(kboFetched); // 같은 사진이라 다시 쓰지 않음
    }

    @Test
    void changedPhotoReplacesTheCopy() {
        reply(MLB, 200, "old", Map.of("content-type", "image/webp", "etag", "\"v1\""));
        PhotoStore store = store(DataMode.MLB);
        store.load("mlb-808982");

        reply(MLB, 200, "new", Map.of("content-type", "image/webp", "etag", "\"v2\""));
        clock.advance(Duration.ofDays(8));
        store.refresh();

        assertThat(new String(saved.get("mlb-808982").getData(), StandardCharsets.UTF_8)).isEqualTo("new");
        assertThat(saved.get("mlb-808982").getEtag()).isEqualTo("\"v2\"");
    }

    @Test
    void notAnImageIsIgnored() {
        reply(MLB, 200, "<html>blocked</html>", Map.of("content-type", "text/html"));

        assertThat(store(DataMode.MLB).load("mlb-808982")).isEmpty();
        assertThat(saved).isEmpty();
    }

    private PhotoStore store(DataMode mode) {
        HttpGetter http = (URI uri, Map<String, String> headers) -> {
            requested.add(uri.toString());
            sentHeaders.add(Map.copyOf(headers));
            Deque<HttpGetter.Reply> queue = replies.get(uri.toString());
            if (queue == null || queue.isEmpty()) {
                return new HttpGetter.Reply(404, Map.of(), new byte[0]);
            }
            return queue.size() > 1 ? queue.poll() : queue.peek();
        };
        PoliteFetcher fetcher = new PoliteFetcher(http, List.of("korleaguer", "ClaudeBot"), clock, clock::advance, false);
        PostingProperties.Current roh = new PostingProperties.Current("노시환", "si-hwan-roh", "한화 이글스", "3루수",
                2000, List.of(), "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00", null, KBO, null);
        PhotoSources sources = new PhotoSources(
                new RosterProperties(List.of(new RosterProperties.Entry("이정후", 808982L, null, null, null, null))),
                new Postings(new PostingProperties(List.of(roh), List.of())),
                new KorleaguerProperties(null, null, null, null,
                        new KorleaguerProperties.Images(true, "https://img.example.com/people/{mlbId}/headshot", null), null));
        return new PhotoStore(sources, repository, fetcher, mode, clock);
    }

    private void reply(String url, int status, String body, Map<String, String> headers) {
        replies.computeIfAbsent(url, k -> new ArrayDeque<>()).clear();
        replies.get(url).add(new HttpGetter.Reply(status, headers, body.getBytes(StandardCharsets.UTF_8)));
    }
}
