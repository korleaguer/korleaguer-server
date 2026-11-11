package com.korleaguer.posting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** posting.yml 검사: 실제 파일의 값(원문으로 확인한 숫자)과, 잘못 적으면 앱이 뜰 때 실패하는지 */
class PostingsTest {

    @Test
    void realFileHasRohAndTheEightPastPostings() throws IOException {
        Postings postings = new Postings(load());

        PostingCase roh = postings.current().getFirst();
        assertThat(roh.nameKo()).isEqualTo("노시환");
        assertThat(roh.searchNames()).first().isEqualTo("노시환");
        assertThat(roh.windowOpens()).isEqualTo(Instant.parse("2026-11-02T13:00:00Z"));
        // MLB Trade Rumors: 미국 중부 12/1 오후 4시 = 한국 12/2 오전 7시
        assertThat(roh.deadline()).isEqualTo(Instant.parse("2026-12-01T22:00:00Z"));

        assertThat(postings.history()).extracting(PastPosting::nameKo, PastPosting::daysBeforeDeadline)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("송성문", 2),
                        org.assertj.core.groups.Tuple.tuple("김혜성", 0),
                        org.assertj.core.groups.Tuple.tuple("이정후", 22),
                        org.assertj.core.groups.Tuple.tuple("고우석", 0),
                        org.assertj.core.groups.Tuple.tuple("김하성", 4),
                        org.assertj.core.groups.Tuple.tuple("나성범", null),
                        org.assertj.core.groups.Tuple.tuple("김광현", 19),
                        org.assertj.core.groups.Tuple.tuple("김재환", null));
    }

    @Test
    void newsIsWatchedFromThreeDaysBeforeOpeningToThreeDaysAfterDeadline() throws IOException {
        Postings postings = new Postings(load());

        assertThat(postings.newsWatch(Instant.parse("2026-10-30T13:00:00Z"))).hasSize(1);
        assertThat(postings.newsWatch(Instant.parse("2026-10-30T12:59:00Z"))).isEmpty();
        assertThat(postings.newsWatch(Instant.parse("2026-12-04T22:00:00Z"))).hasSize(1);
        assertThat(postings.newsWatch(Instant.parse("2026-12-04T22:01:00Z"))).isEmpty();
    }

    @Test
    void koreanNameIsAlwaysSearchedAndDuplicatesDrop() {
        Postings postings = new Postings(new PostingProperties(List.of(current(
                List.of("Si-Hwan Roh", "노시환", " ", "Si-Hwan Roh"), "2026-11-02T08:00:00-05:00",
                "2026-12-01T17:00:00-05:00", null)), List.of()));

        assertThat(postings.current().getFirst().searchNames()).containsExactly("노시환", "Si-Hwan Roh");
    }

    @Test
    void missingOrBrokenTimesFailAtStartup() {
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", null, null)), List.of())))
                .hasMessageContaining("posting.current 노시환: deadline is required");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02 08:00", "2026-12-01T17:00:00-05:00", null)), List.of())))
                .hasMessageContaining("window-opens must be an ISO date-time with offset");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-12-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00", null)), List.of())))
                .hasMessageContaining("deadline must be after window-opens");
    }

    @Test
    void signedResultNeedsAKnownTeamAndTheDealNumbers() {
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("SIGNED", "XYZ", "2026-11-28", 4, 1L))), List.of())))
                .hasMessageContaining("unknown mlb-team: XYZ");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("SIGNED", "SD", "2026-11-28", null, 1L))), List.of())))
                .hasMessageContaining("years must be 1 or more");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("MAYBE", null, null, null, null))), List.of())))
                .hasMessageContaining("result.outcome must be SIGNED or NO_DEAL");
    }

    @Test
    void pastAgreementCannotComeAfterTheDeadline() {
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(), List.of(
                new PostingProperties.Past("이정후", 2023, "SF", "2024-01-03", "2024-01-04", 6, 1L, null)))))
                .hasMessageContaining("posting.history 이정후: agreed-on must not be after deadline");
    }

    private static PostingProperties.Current current(List<String> names, String opens, String deadline,
                                                     PostingProperties.Result result) {
        return new PostingProperties.Current("노시환", "한화 이글스", "3루수", 2000, names, opens, deadline, null, result);
    }

    private static PostingProperties load() throws IOException {
        var sources = new YamlPropertySourceLoader().load("posting", new ClassPathResource("roster/posting.yml"));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("posting", PostingProperties.class).get();
    }
}
