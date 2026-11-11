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
    void realFileHasRohAndTheNineSignedPostings() throws IOException {
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
                        org.assertj.core.groups.Tuple.tuple("김광현", 19),
                        // 2017년까지의 옛 제도: 최고 입찰 구단과 단독 협상한 마감까지
                        org.assertj.core.groups.Tuple.tuple("박병호", 7),
                        org.assertj.core.groups.Tuple.tuple("강정호", 8),
                        org.assertj.core.groups.Tuple.tuple("류현진", 0));
        // KBO 선수 번호(포스팅 당시 KBO 공식 사진을 찾는 데 쓴다). 옛 제도 세 명은 MLB 사진만 쓰거나 사진이 없어 적지 않았다
        assertThat(postings.history()).extracting(PastPosting::kboId)
                .containsExactly(65357L, 67304L, 67341L, 67119L, 64300L, 77829L, null, null, null);
        // 계약 구단 마지막 시즌 MLB 공식 사진(김하성·고우석은 인터넷 보관소의 2024 샌디에이고 사본).
        // 류현진은 다저스 시절 사진을 아직 못 구해 사진이 없다(화면은 이름 원)
        assertThat(postings.history()).extracting(PastPosting::mlbId, PastPosting::photoSeason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(823550L, 2026),
                        org.assertj.core.groups.Tuple.tuple(808975L, 2026),
                        org.assertj.core.groups.Tuple.tuple(808982L, 2026),
                        org.assertj.core.groups.Tuple.tuple(808970L, 2024),
                        org.assertj.core.groups.Tuple.tuple(673490L, 2024),
                        org.assertj.core.groups.Tuple.tuple(547942L, 2021),
                        org.assertj.core.groups.Tuple.tuple(666560L, 2016),
                        org.assertj.core.groups.Tuple.tuple(628356L, 2019),
                        org.assertj.core.groups.Tuple.tuple(null, null));
        assertThat(postings.history().subList(0, 8)).allSatisfy(p -> assertThat(p.photoUrl()).startsWith("https://"));
        assertThat(postings.history().get(8).photoUrl()).isNull();
    }

    @Test
    void historyPhotoNeedsMlbIdAndSeason() {
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(), List.of(
                new PostingProperties.Past("김하성", 2020, "SD", "2021-01-01", "2020-12-28", 4, 1L, null, null,
                        null, 2024, "https://archive.example/kim.jpg", null)))))
                .hasMessageContaining("posting.history 김하성: mlb-id is required when photo-url is set");
    }

    @Test
    void realFileHasTheExchangeRateOfEachAgreementDay() throws IOException {
        Postings postings = new Postings(load());

        // ECB 기준 환율(KRW ÷ USD) 합의일 값. 보장액 × 환율 = 당시 보도된 원화 규모(이정후 약 1,480억, 김광현 약 93억)
        assertThat(postings.history()).extracting(p -> p.nameKo(), p -> p.result().krwPerUsd())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("송성문", 1478.72),
                        org.assertj.core.groups.Tuple.tuple("김혜성", 1468.78),
                        org.assertj.core.groups.Tuple.tuple("이정후", 1309.89),
                        org.assertj.core.groups.Tuple.tuple("고우석", 1311.73),
                        org.assertj.core.groups.Tuple.tuple("김하성", 1097.36),
                        org.assertj.core.groups.Tuple.tuple("김광현", 1164.11),
                        org.assertj.core.groups.Tuple.tuple("박병호", 1159.43),
                        org.assertj.core.groups.Tuple.tuple("강정호", 1085.42),
                        org.assertj.core.groups.Tuple.tuple("류현진", 1083.05));
        PostingResult lee = postings.history().get(2).result();
        assertThat(Math.round(lee.guaranteedUsd() * lee.krwPerUsd() / 1e8)).isEqualTo(1480);
    }

    @Test
    void exchangeRateMustBeWonPerDollar() {
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("SIGNED", "SD", "2026-11-28", 4, 1L, 0.00068))), List.of())))
                .hasMessageContaining("result: krw-per-usd must be won per dollar between 500 and 3000: 6.8E-4");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(), List.of(
                new PostingProperties.Past("이정후", 2023, "SF", "2024-01-03", "2023-12-12", 6, 1L, null, null, null,
                        null, null, 13098.9)))))
                .hasMessageContaining("posting.history 이정후: krw-per-usd must be won per dollar");

        Postings ok = new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("SIGNED", "SD", "2026-11-28", 4, 1L, null))), List.of()));
        assertThat(ok.current().getFirst().result().krwPerUsd()).isNull(); // 적지 않으면 화면은 달러만
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
                new PostingProperties.Result("SIGNED", "XYZ", "2026-11-28", 4, 1L, null))), List.of())))
                .hasMessageContaining("unknown mlb-team: XYZ");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("SIGNED", "SD", "2026-11-28", null, 1L, null))), List.of())))
                .hasMessageContaining("years must be 1 or more");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("MAYBE", null, null, null, null, null))), List.of())))
                .hasMessageContaining("result.outcome must be SIGNED or NO_DEAL");
    }

    @Test
    void pastAgreementCannotComeAfterTheDeadline() {
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(), List.of(
                new PostingProperties.Past("이정후", 2023, "SF", "2024-01-03", "2024-01-04", 6, 1L, null, null, null, null, null, null)))))
                .hasMessageContaining("posting.history 이정후: agreed-on must not be after deadline");
    }

    private static PostingProperties.Current current(List<String> names, String opens, String deadline,
                                                     PostingProperties.Result result) {
        return new PostingProperties.Current("노시환", null, "한화 이글스", "3루수", 2000, names, opens, deadline, null,
                null,
                result);
    }

    private static PostingProperties load() throws IOException {
        var sources = new YamlPropertySourceLoader().load("posting", new ClassPathResource("roster/posting.yml"));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("posting", PostingProperties.class).get();
    }
}
