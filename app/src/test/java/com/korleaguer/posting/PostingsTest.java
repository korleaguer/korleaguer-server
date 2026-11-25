package com.korleaguer.posting;

import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("포스팅 파일에 노시환과 지난 포스팅 아홉 명의 값이 들어 있다")
    void realFileHasRohAndTheNineSignedPostings() throws IOException {
        // when
        Postings postings = new Postings(load());

        // then
        PostingCase roh = postings.current().getFirst();
        assertThat(roh.nameKo()).isEqualTo("노시환");
        assertThat(roh.searchNames()).first().isEqualTo("노시환");
        assertThat(roh.windowOpens()).isEqualTo(Instant.parse("2026-11-02T13:00:00Z"));
        // MLB Trade Rumors: 미국 중부 12/1 오후 4시 = 한국 12/2 오전 7시
        assertThat(roh.deadline()).isEqualTo(Instant.parse("2026-12-01T22:00:00Z"));
        // 사진: KBO 2026 공식 등록 사진(KBO 사진 서버 판은 94×118)과 같은 사진의 네이버 스포츠 큰 판(210×262).
        // 화면은 그 편집본(/photos/kbo-si-hwan-roh-2026)
        assertThat(roh.slug()).isEqualTo("si-hwan-roh");
        assertThat(roh.photoSeason()).isEqualTo(2026);
        assertThat(roh.photoUrl()).isEqualTo("https://sports-phinf.pstatic.net/player/kbo/default/69737.png");

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
        // KBO 선수 번호(포스팅 당시 KBO 공식 사진을 찾는 데 쓴다). 옛 제도 세 명은 MLB 사진만 써서 적지 않았다
        assertThat(postings.history()).extracting(PastPosting::kboId)
                .containsExactly(65357L, 67304L, 67341L, 67119L, 64300L, 77829L, null, null, null);
        // 계약 구단 마지막 시즌 MLB 공식 사진(김하성·고우석은 인터넷 보관소의 2024 샌디에이고 사본,
        // 류현진은 인터넷 보관소의 2019 다저스 사본)
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
                        org.assertj.core.groups.Tuple.tuple(547943L, 2019));
        assertThat(postings.history()).allSatisfy(p -> assertThat(p.photoUrl()).startsWith("https://"));
    }

    @Test
    @DisplayName("지난 포스팅에 사진을 적으면 MLB 선수 번호와 올바른 연도가 있어야 한다")
    void historyPhotoNeedsMlbIdAndSeason() {
        // when // then
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(), List.of(
                new PostingProperties.Past("김하성", 2020, "SD", "2021-01-01", "2020-12-28", 4, 1L, null, null,
                        null, 2024, "https://archive.example/kim.jpg", null)))))
                .hasMessageContaining("posting.history 김하성: mlb-id is required when photo-url is set");
        // 적었지만 연도가 아닌 값은 '필요하다'가 아니라 무엇이 틀렸는지 말한다
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(), List.of(
                new PostingProperties.Past("김하성", 2020, "SD", "2021-01-01", "2020-12-28", 4, 1L, null, null,
                        673490L, 24, "https://archive.example/kim.jpg", null)))))
                .hasMessageContaining("posting.history 김하성: photo-season must be a year between 1990 and 2100: 24");
    }

    @Test
    @DisplayName("사진 이름을 만들 값이 빠진 채로는 사진을 적지 못한다")
    void recordsRefuseAPhotoTheyCannotName() {
        // when // then
        // yml을 거치지 않고 만들어도(테스트·다른 코드) 사진 주소를 만들 값이 없으면 바로 막는다
        assertThatThrownBy(() -> new PostingCase("노시환", "si-hwan-roh", "한화 이글스", "3루수", 2000, List.of(),
                Instant.parse("2026-11-02T13:00:00Z"), Instant.parse("2026-12-01T22:00:00Z"), null, null,
                "https://sports-phinf.pstatic.net/player/kbo/default/69737.png", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("photoUrl needs slug and photoSeason");
        assertThatThrownBy(() -> new PastPosting("김하성", 2020, java.time.LocalDate.parse("2021-01-01"),
                PostingResult.noDeal(), null, null, null, 2024, "https://archive.example/kim.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("photoUrl needs mlbId and photoSeason");
        // 사진이 없으면 slug·시즌이 없어도 된다
        assertThat(new PostingCase("노시환", null, "한화 이글스", "3루수", 2000, List.of(),
                Instant.parse("2026-11-02T13:00:00Z"), Instant.parse("2026-12-01T22:00:00Z"), null, null, null, null)
                .photoUrl()).isNull();
    }

    @Test
    @DisplayName("지금 포스팅 사진에는 연도와 규칙에 맞는 영문 이름이 있어야 한다")
    void currentPhotoNeedsASeasonAndASlugThatFitsThePhotoName() {
        // when // then
        // 사진 이름 kbo-{slug}-{photo-season}: 시즌이 없으면 사진을 바꿔도 주소가 그대로라 하루 동안 옛 사진이 남는다
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(withPhoto("si-hwan-roh", null)), List.of())))
                .hasMessageContaining("posting.current 노시환: photo-season (year of the photo) is required when photo-url is set");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(withPhoto("si-hwan-roh", 26)), List.of())))
                .hasMessageContaining("posting.current 노시환: photo-season must be a year between 1990 and 2100: 26");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(withPhoto(null, 2026)), List.of())))
                .hasMessageContaining("posting.current 노시환: slug is required when photo-url is set");

        // given
        // api·웹의 사진 이름 규칙('kbo-' 뒤 40자)에 '-2026'까지 들어가게 35자까지
        String longest = "a".repeat(35);

        // when // then
        assertThat(new Postings(new PostingProperties(List.of(withPhoto(longest, 2026)), List.of()))
                .current().getFirst().slug()).isEqualTo(longest);
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(withPhoto(longest + "a", 2026)), List.of())))
                .hasMessageContaining("slug must start with a lowercase letter");
        // 숫자로 시작하면 지난 선배의 시즌 사진 이름(kbo-{KBO 선수 번호}-{시즌})과 겹칠 수 있다
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(withPhoto("69737", 2026)), List.of())))
                .hasMessageContaining("slug must start with a lowercase letter");
    }

    @Test
    @DisplayName("지난 포스팅마다 합의한 날의 환율이 들어 있다")
    void realFileHasTheExchangeRateOfEachAgreementDay() throws IOException {
        // when
        Postings postings = new Postings(load());

        // then
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
    @DisplayName("환율은 1달러당 원화 값만 받고 적지 않으면 비워 둔다")
    void exchangeRateMustBeWonPerDollar() {
        // when // then
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("SIGNED", "SD", "2026-11-28", 4, 1L, 0.00068))), List.of())))
                .hasMessageContaining("result: krw-per-usd must be won per dollar between 500 and 3000: 6.8E-4");
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(), List.of(
                new PostingProperties.Past("이정후", 2023, "SF", "2024-01-03", "2023-12-12", 6, 1L, null, null, null,
                        null, null, 13098.9)))))
                .hasMessageContaining("posting.history 이정후: krw-per-usd must be won per dollar");

        // when
        Postings ok = new Postings(new PostingProperties(List.of(current(List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                new PostingProperties.Result("SIGNED", "SD", "2026-11-28", 4, 1L, null))), List.of()));

        // then
        assertThat(ok.current().getFirst().result().krwPerUsd()).isNull(); // 적지 않으면 화면은 달러만
    }

    @Test
    @DisplayName("포스팅 기사는 시작 사흘 전부터 마감 사흘 뒤까지 지켜본다")
    void newsIsWatchedFromThreeDaysBeforeOpeningToThreeDaysAfterDeadline() throws IOException {
        // given
        Postings postings = new Postings(load());

        // when // then
        assertThat(postings.newsWatch(Instant.parse("2026-10-30T13:00:00Z"))).hasSize(1);
        assertThat(postings.newsWatch(Instant.parse("2026-10-30T12:59:00Z"))).isEmpty();
        assertThat(postings.newsWatch(Instant.parse("2026-12-04T22:00:00Z"))).hasSize(1);
        assertThat(postings.newsWatch(Instant.parse("2026-12-04T22:01:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("한글 이름은 늘 검색하고 겹치는 이름은 한 번만 쓴다")
    void koreanNameIsAlwaysSearchedAndDuplicatesDrop() {
        // when
        Postings postings = new Postings(new PostingProperties(List.of(current(
                List.of("Si-Hwan Roh", "노시환", " ", "Si-Hwan Roh"), "2026-11-02T08:00:00-05:00",
                "2026-12-01T17:00:00-05:00", null)), List.of()));

        // then
        assertThat(postings.current().getFirst().searchNames()).containsExactly("노시환", "Si-Hwan Roh");
    }

    @Test
    @DisplayName("포스팅 시작·마감 시각이 빠지거나 잘못되면 무엇이 틀렸는지 말하며 막는다")
    void missingOrBrokenTimesFailAtStartup() {
        // when // then
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
    @DisplayName("포스팅 결과의 구단, 계약 기간, 종류가 잘못되면 막는다")
    void signedResultNeedsAKnownTeamAndTheDealNumbers() {
        // when // then
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
    @DisplayName("지난 포스팅의 합의일이 마감보다 늦으면 막는다")
    void pastAgreementCannotComeAfterTheDeadline() {
        // when // then
        assertThatThrownBy(() -> new Postings(new PostingProperties(List.of(), List.of(
                new PostingProperties.Past("이정후", 2023, "SF", "2024-01-03", "2024-01-04", 6, 1L, null, null, null, null, null, null)))))
                .hasMessageContaining("posting.history 이정후: agreed-on must not be after deadline");
    }

    private static PostingProperties.Current current(List<String> names, String opens, String deadline,
                                                     PostingProperties.Result result) {
        return new PostingProperties.Current("노시환", null, "한화 이글스", "3루수", 2000, names, opens, deadline, null,
                null, null, result);
    }

    private static PostingProperties.Current withPhoto(String slug, Integer photoSeason) {
        return new PostingProperties.Current("노시환", slug, "한화 이글스", "3루수", 2000, List.of(),
                "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00", null, photoSeason,
                "https://sports-phinf.pstatic.net/player/kbo/default/69737.png", null);
    }

    private static PostingProperties load() throws IOException {
        var sources = new YamlPropertySourceLoader().load("posting", new ClassPathResource("roster/posting.yml"));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("posting", PostingProperties.class).get();
    }
}
