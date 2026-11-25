package com.korleaguer.api.records;

import com.korleaguer.domain.KoreanMlbSeason.Counts;
import com.korleaguer.domain.PlayerSeasonMetric.Group;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한국인 메이저리거 역대 기록 계산(이슈 #177). 2026 실제 기록 일부로 확인한다:
 * 이정후 통산 안타 38(2024)+149(2025)+149(2026) = 336, 강정호 233, 최희섭 220, 최지만 367.
 */
class KoreanRecordsTest {

    private static final KoreanRecords.Person LEE = new KoreanRecords.Person(808982, 1L, "이정후", "/photos/mlb-808982",
            Group.HITTING, true);
    private static final KoreanRecords.Person CHOI = new KoreanRecords.Person(596847, null, "최지만", null, Group.HITTING,
            false);
    private static final KoreanRecords.Person KANG = new KoreanRecords.Person(628356, null, "강정호", null, Group.HITTING,
            false);
    private static final KoreanRecords.Person HEE = new KoreanRecords.Person(400292, null, "최희섭", null, Group.HITTING,
            false);
    private static final KoreanRecords.Person PARK = new KoreanRecords.Person(120221, null, "박찬호", null, Group.PITCHING,
            false);

    private static Counts hitting(int games, int hits, int homeRuns) {
        return new Counts(games, hits, homeRuns, 0, 0, null, null, null);
    }

    private static Counts pitching(int wins, int strikeouts) {
        return new Counts(1, null, null, null, null, wins, 0, strikeouts);
    }

    private static RecordsResponse build(Map<Long, List<KoreanRecords.GameLine>> games) {
        Map<Long, List<KoreanRecords.SeasonLine>> seasons = Map.of(
                LEE.mlbId(), List.of(new KoreanRecords.SeasonLine(2024, hitting(37, 38, 2)),
                        new KoreanRecords.SeasonLine(2025, hitting(150, 149, 8)),
                        new KoreanRecords.SeasonLine(2026, hitting(143, 149, 10))),
                CHOI.mlbId(), List.of(new KoreanRecords.SeasonLine(2019, hitting(525, 367, 67))),
                KANG.mlbId(), List.of(new KoreanRecords.SeasonLine(2016, hitting(297, 233, 46))),
                HEE.mlbId(), List.of(new KoreanRecords.SeasonLine(2004, hitting(363, 220, 40))),
                PARK.mlbId(), List.of(new KoreanRecords.SeasonLine(2000, pitching(18, 217)),
                        new KoreanRecords.SeasonLine(2001, pitching(15, 218))));
        return response(KoreanRecords.build(2026, List.of(LEE, CHOI, KANG, HEE, PARK), seasons, games));
    }

    /** 계산 결과 표를 응답으로 싼다(RecordsService와 같다) */
    private static RecordsResponse response(KoreanRecords.Tables tables) {
        return new RecordsResponse("sample", 2026, Instant.parse("2026-11-17T00:00:00Z"), tables.categories(),
                tables.next(), tables.passed(), tables.singleSeason());
    }

    @Test
    @DisplayName("시즌 기록을 더해 통산 순위를 매기고 타자와 투수를 나눈다")
    void ranksCareerTotalsAndKeepsHittersAndPitchersApart() {
        // when
        RecordsResponse records = build(Map.of());

        // then
        RecordCategory hits = category(records, "hits");
        assertThat(hits.group()).isEqualTo("HITTING");
        assertThat(hits.unit()).isEqualTo("안타");
        assertThat(hits.leaders()).extracting(RecordLeader::nameKo)
                .containsExactly("최지만", "이정후", "강정호", "최희섭");
        assertThat(hits.leaders()).extracting(RecordLeader::value).containsExactly(367, 336, 233, 220);
        RecordLeader lee = hits.leaders().get(1);
        assertThat(lee.rank()).isEqualTo(2);
        assertThat(lee.active()).isTrue();
        assertThat(lee.playerId()).isEqualTo(1L);
        assertThat(lee.firstSeason()).isEqualTo(2024);
        assertThat(lee.lastSeason()).isEqualTo(2026);

        // 투수 기록에는 투수만, 승은 시즌을 더한다
        assertThat(category(records, "wins").leaders()).extracting(RecordLeader::nameKo)
                .containsExactly("박찬호");
        assertThat(category(records, "wins").leaders().getFirst().value()).isEqualTo(33);
        // 0인 기록은 순위에 넣지 않는다(도루는 모두 0)
        assertThat(category(records, "stolenBases").leaders()).isEmpty();
    }

    @Test
    @DisplayName("기록이 같으면 공동 순위이고 먼저 데뷔한 선수가 앞에 온다")
    void sameValueSharesTheRank() {
        // given
        Map<Long, List<KoreanRecords.SeasonLine>> seasons = Map.of(
                LEE.mlbId(), List.of(new KoreanRecords.SeasonLine(2026, hitting(10, 20, 1))),
                KANG.mlbId(), List.of(new KoreanRecords.SeasonLine(2015, hitting(30, 20, 5))),
                HEE.mlbId(), List.of(new KoreanRecords.SeasonLine(2004, hitting(20, 10, 3))));

        // when
        RecordsResponse records = response(KoreanRecords.build(2026, List.of(LEE, KANG, HEE), seasons, Map.of()));

        // then
        // 안타 20개 둘은 공동 1위, 먼저 데뷔한 강정호가 앞. 다음은 3위
        assertThat(category(records, "hits").leaders()).extracting(RecordLeader::nameKo, RecordLeader::rank)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("강정호", 1),
                        org.assertj.core.groups.Tuple.tuple("이정후", 1),
                        org.assertj.core.groups.Tuple.tuple("최희섭", 3));
    }

    @Test
    @DisplayName("현역 선수에게만 바로 위 선수와 넘기까지 남은 수를 알린다")
    void nextIsTheClosestPlayerAboveForActivePlayersOnly() {
        // when
        RecordsResponse records = build(Map.of());

        // then
        // 이정후 안타 336 → 바로 위 최지만 367, 넘으려면 32개, 넘으면 1위
        RecordNext hits = next(records, "hits");
        assertThat(hits.nameKo()).isEqualTo("이정후");
        assertThat(hits.value()).isEqualTo(336);
        assertThat(hits.rank()).isEqualTo(2);
        assertThat(hits.targetNameKo()).isEqualTo("최지만");
        assertThat(hits.targetValue()).isEqualTo(367);
        assertThat(hits.toPass()).isEqualTo(32);
        assertThat(hits.rankAfter()).isEqualTo(1);
        // 경기 330 → 바로 위는 강정호 297이 아니라 최희섭 363
        assertThat(next(records, "games").targetNameKo()).isEqualTo("최희섭");
        assertThat(next(records, "games").toPass()).isEqualTo(34);
        // 현역이 아닌 선수에게는 다음에 넘을 선수가 없다
        assertThat(records.next()).allMatch(n -> n.nameKo().equals("이정후"));
        // 넘기 쉬운(남은 수 ÷ 상대 값이 작은) 순: 안타 32/367 < 경기 34/363 < 홈런 21/40(최희섭)
        assertThat(records.next()).extracting(RecordNext::category)
                .containsExactly("hits", "games", "homeRuns");
    }

    @Test
    @DisplayName("다른 선수의 통산 안타를 넘은 경기 날짜를 최근 것부터 찾는다")
    void passedFindsTheGameThatCrossedAFormerPlayersTotal() {
        // when
        // 지난 시즌까지 187안타. 4월 1일 +20 = 207, 5월 1일 +14 = 221(최희섭 220을 넘음), 5월 17일 +13 = 234(강정호 233)
        RecordsResponse records = build(Map.of(LEE.mlbId(), List.of(
                game("2026-04-01", 20), game("2026-05-01", 14), game("2026-05-17", 13), game("2026-09-27", 102))));

        // then
        assertThat(records.passed()).extracting(RecordPassed::dateKst, RecordPassed::passedNameKo,
                        RecordPassed::category)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2026-05-17"), "강정호", "hits"),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2026-05-01"), "최희섭", "hits"));
        assertThat(records.passed().getFirst().passedValue()).isEqualTo(233);
        assertThat(records.passed().getFirst().unit()).isEqualTo("안타");
    }

    @Test
    @DisplayName("더블헤더 두 번째 경기에서 넘었으면 두 번째 경기가 넘은 경기다")
    void doubleHeaderKeepsTheGameThatCrossed() {
        // when
        // 지난 시즌까지 187안타. 첫 경기 +33 = 220(최희섭과 같음), 둘째 경기 +1 = 221(최희섭 220을 넘음)
        RecordsResponse records = build(Map.of(LEE.mlbId(), List.of(
                game(11, "2026-06-18", 33), game(12, "2026-06-18", 1))));

        // then
        assertThat(records.passed()).extracting(RecordPassed::passedNameKo, RecordPassed::gameId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("최희섭", 12L));
    }

    @Test
    @DisplayName("오늘 경기까지 더한 기록이 더 많으면 그 값을 통산에 쓴다")
    void todaysGamesCountBeforeTheDailySeasonLineArrives() {
        // when
        // 저장한 2026 줄은 149안타인데 경기 기록 합이 150이면(오늘 경기를 아직 yearByYear로 못 받음) 통산은 337
        RecordsResponse records = build(Map.of(LEE.mlbId(), List.of(game("2026-09-27", 150))));

        // then
        assertThat(category(records, "hits").leaders().get(1).value()).isEqualTo(337);
        assertThat(next(records, "hits").toPass()).isEqualTo(31);

        // when
        // 경기 기록 합이 더 적으면(빠진 경기) 저장한 줄을 그대로 쓴다
        RecordsResponse fewer = build(Map.of(LEE.mlbId(), List.of(game("2026-09-27", 100))));

        // then
        assertThat(category(fewer, "hits").leaders().get(1).value()).isEqualTo(336);
    }

    @Test
    @DisplayName("통산 안타가 같아지기만 하면 넘은 기록으로 치지 않는다")
    void reachingTheSameValueIsNotPassing() {
        // when
        // 187 + 33 = 220: 최희섭과 같아졌을 뿐 넘지 않았다
        RecordsResponse records = build(Map.of(LEE.mlbId(), List.of(game("2026-04-30", 33))));

        // then
        assertThat(records.passed()).isEmpty();
    }

    @Test
    @DisplayName("한 시즌 최다는 선수가 아니라 시즌마다 한 줄씩 매긴다")
    void singleSeasonListsSeasonsNotPlayers() {
        // when
        RecordsResponse records = build(Map.of());

        // then
        RecordBest hits = records.singleSeason().stream().filter(b -> b.key().equals("hits")).findFirst()
                .orElseThrow();
        // 시즌마다 한 줄: 이정후 2025·2026 149안타가 공동 4위로 따로 나온다(먼저 시즌이 앞)
        assertThat(hits.rows()).extracting(RecordSeasonRow::nameKo, RecordSeasonRow::season,
                        RecordSeasonRow::rank)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("최지만", 2019, 1),
                        org.assertj.core.groups.Tuple.tuple("강정호", 2016, 2),
                        org.assertj.core.groups.Tuple.tuple("최희섭", 2004, 3),
                        org.assertj.core.groups.Tuple.tuple("이정후", 2025, 4),
                        org.assertj.core.groups.Tuple.tuple("이정후", 2026, 4),
                        org.assertj.core.groups.Tuple.tuple("이정후", 2024, 6));
        // 경기·타점은 한 시즌 최다를 내지 않는다
        assertThat(records.singleSeason()).extracting(RecordBest::key)
                .containsExactly("hits", "homeRuns", "stolenBases", "wins", "strikeouts", "saves");
    }

    private static KoreanRecords.GameLine game(String date, int hits) {
        return game(1, date, hits);
    }

    private static KoreanRecords.GameLine game(long gameId, String date, int hits) {
        return new KoreanRecords.GameLine(gameId, LocalDate.parse(date), hitting(1, hits, 0));
    }

    private static RecordCategory category(RecordsResponse records, String key) {
        return records.categories().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    private static RecordNext next(RecordsResponse records, String category) {
        return records.next().stream().filter(n -> n.category().equals(category)).findFirst().orElseThrow();
    }
}
