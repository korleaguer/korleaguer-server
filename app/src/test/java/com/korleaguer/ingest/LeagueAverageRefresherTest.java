package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbStat;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.LeagueAverage;
import com.korleaguer.domain.LeagueAverageRepository;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.MinorSeasonLevel;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 리그 평균 받기(이슈 #233): 받는 (시즌, 단계) 조합, 없는 줄만 받기, 지난 시즌·비시즌은 다시 받지 않기, 시즌 중 하루 한 번,
 * 실패하면 그 조합만 건너뛰기, 403/429 중단, 값이 안 나오는 응답은 저장하지 않기
 */
class LeagueAverageRefresherTest {

    // 2026-11-01(UTC). 지금 시즌은 2026
    private static final Instant NOW = Instant.parse("2026-11-01T11:00:00Z");
    private static final String MLB = "MLB";

    private final MutableClock clock = new MutableClock(NOW);
    private final LeagueAverageRepository averages = mock(LeagueAverageRepository.class);
    private final AppearanceRepository appearances = mock(AppearanceRepository.class);
    private final MinorGameLogRepository minorGameLogs = mock(MinorGameLogRepository.class);
    private final CurrentSeason currentSeason = mock(CurrentSeason.class);
    /** DB 흉내: 저장된 줄 */
    private final List<LeagueAverage> rows = new ArrayList<>();
    /** MLB에 물은 것: "시즌 sportId 묶음" */
    private final List<String> asked = new ArrayList<>();
    private final Set<String> failing = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();
    private final Set<String> empty = new HashSet<>();

    private LeagueAverageRefresher refresher;

    @BeforeEach
    void setUp() {
        when(currentSeason.current()).thenReturn(2026);
        when(averages.findAll()).thenAnswer(invocation -> List.copyOf(rows));
        when(averages.save(any())).thenAnswer(invocation -> {
            LeagueAverage row = invocation.getArgument(0);
            if (!rows.contains(row)) {
                rows.add(row);
            }
            return row;
        });
        // 메이저 기록은 2026, 마이너는 2026 트리플A·더블A
        when(appearances.findSeasons()).thenReturn(List.of(2026));
        when(minorGameLogs.findSeasonLevels()).thenReturn(List.of(new MinorSeasonLevel(2026, 2026, "AAA"),
                new MinorSeasonLevel(2026, 2026, "AA")));
        refresher = new LeagueAverageRefresher(client(), averages, appearances, minorGameLogs, currentSeason, clock);
    }

    @Test
    @DisplayName("팀 합계로 리그 평균을 내고 타석이나 아웃이 없으면 비운다")
    void addsTeamTotalsIntoLeagueAverages() {
        // given
        // 두 팀: 타석 1,000·득점 120, 아웃 2,700·자책 450 → 타석당 0.120, 평균자책점 4.50
        List<MlbGameLogSplit> hitting = List.of(team(hitting(400, 100, 20, 2, 12, 40, 5, 3, 450, 50)),
                team(hitting(480, 120, 25, 3, 15, 50, 6, 4, 550, 70)));
        List<MlbGameLogSplit> pitching = List.of(team(pitching(1350, 200)), team(pitching(1350, 250)));

        // when
        LeagueAverage.Values values = LeagueAverageRefresher.fromTotals(hitting, pitching);

        // then
        assertThat(values.runsPerPa()).isCloseTo(0.120, within(1e-9));
        assertThat(values.era()).isCloseTo(4.50, within(1e-9));
        assertThat(values.woba()).isBetween(0.30, 0.36);

        // when // then
        // 타석이나 아웃이 없으면 값이 없다
        assertThat(LeagueAverageRefresher.fromTotals(List.of(), pitching)).isNull();
        assertThat(LeagueAverageRefresher.fromTotals(hitting, List.of())).isNull();
        assertThat(LeagueAverageRefresher.fromTotals(null, null)).isNull();
    }

    @Test
    @DisplayName("우리 기록이 있는 시즌과 단계만 받고 기록이 없어도 지금 시즌 MLB는 받는다")
    void neededCombinationsComeFromOurRecords() {
        // given
        // 메이저 기록 2024·2026, 마이너 2025(트리플A)·2026(트리플A·루키). 단계를 모르는 경기와 평균을 두지 않는 단계도 있다
        when(appearances.findSeasons()).thenReturn(List.of(2024, 2026));
        when(minorGameLogs.findSeasonLevels()).thenReturn(List.of(new MinorSeasonLevel(2025, 2025, "AAA"),
                new MinorSeasonLevel(2026, 2026, "AAA"), new MinorSeasonLevel(2026, 2026, "ROK"),
                new MinorSeasonLevel(2023, 2023, null), new MinorSeasonLevel(2026, 2026, "WIN")));

        // when // then
        // 새 시즌부터, 같은 시즌은 메이저부터 단계 순. MLB는 기록(메이저든 마이너든)이 있는 시즌마다 받는다
        assertThat(refresher.needed(2026)).containsExactly(key(2026, MLB), key(2026, "AAA"), key(2026, "ROK"),
                key(2025, MLB), key(2025, "AAA"), key(2024, MLB), key(2023, MLB));

        // given
        // 기록이 하나도 없어도 지금 시즌의 MLB는 받는다(내 선수 카드·비시즌 홈이 묻는다)
        when(appearances.findSeasons()).thenReturn(List.of());
        when(minorGameLogs.findSeasonLevels()).thenReturn(List.of());

        // when // then
        assertThat(refresher.needed(2027)).containsExactly(key(2027, MLB));
    }

    @Test
    @DisplayName("마이너 경기 날짜의 해가 시즌과 다르면 두 해의 평균을 모두 받는다")
    void minorGameYearIsAlsoNeeded() {
        // given
        // 내 선수 카드는 마이너 경기의 공식 날짜 연도로 묻는다. 시즌과 다르면 둘 다 받는다
        when(minorGameLogs.findSeasonLevels()).thenReturn(List.of(new MinorSeasonLevel(2026, 2027, "AA")));

        // when // then
        assertThat(refresher.needed(2026)).containsExactly(key(2027, MLB), key(2027, "AA"), key(2026, MLB),
                key(2026, "AA"));
    }

    @Test
    @DisplayName("없는 평균을 타격과 투구로 한 번씩 받고 비시즌에는 더 받지 않는다")
    void fetchesMissingRowsWithTwoRequestsEach() {
        // when // then
        assertThat(refresher.refresh(false)).isEqualTo("리그 평균 3개");

        assertThat(asked).containsExactly("2026 1 hitting", "2026 1 pitching", "2026 11 hitting", "2026 11 pitching",
                "2026 12 hitting", "2026 12 pitching");
        assertThat(rows).extracting(LeagueAverage::getLevel).containsExactly(MLB, "AAA", "AA");
        LeagueAverage tripleA = rows.get(1);
        assertThat(tripleA.getSeason()).isEqualTo(2026);
        assertThat(tripleA.getFetchedAt()).isEqualTo(NOW);
        // 트리플A(sportId 11) 팀 합계: 타석 550·득점 81, 아웃 1,350·자책 261
        assertThat(tripleA.values().runsPerPa()).isCloseTo(81 / 550.0, within(1e-12));
        assertThat(tripleA.values().era()).isCloseTo(261 * 27.0 / 1350, within(1e-12));

        // when // then
        // 다 있으면 비시즌에는 더 받지 않는다. 날이 바뀌어도 마찬가지다
        assertThat(refresher.refresh(false)).isEmpty();

        // given
        clock.advance(Duration.ofDays(3));

        // when // then
        assertThat(refresher.refresh(false)).isEmpty();
        assertThat(asked).hasSize(6);
    }

    @Test
    @DisplayName("시즌 중에는 지금 시즌 평균을 하루 한 번만 다시 받는다")
    void inSeasonRefreshesTheCurrentSeasonOncePerUtcDay() {
        // given
        clock.set(Instant.parse("2026-07-01T05:00:00Z"));

        // when
        refresher.refresh(true);

        // then
        assertThat(asked).hasSize(6);
        double before = rows.getFirst().values().woba();

        // given
        // 같은 날(UTC) 다시 돌면 묻지 않는다
        clock.set(Instant.parse("2026-07-01T23:50:00Z"));

        // when // then
        assertThat(refresher.refresh(true)).isEmpty();
        assertThat(asked).hasSize(6);

        // given
        // 다음 날(UTC): 지금 시즌 줄을 모두 다시 받아 덮어쓴다(줄 수는 그대로)
        clock.set(Instant.parse("2026-07-02T00:10:00Z"));

        // when // then
        assertThat(refresher.refresh(true)).isEqualTo("리그 평균 3개");
        assertThat(asked).hasSize(12);
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().getFetchedAt()).isEqualTo(Instant.parse("2026-07-02T00:10:00Z"));
        assertThat(rows.getFirst().values().woba()).isEqualTo(before);
    }

    @Test
    @DisplayName("비시즌에는 오래전에 받은 지금 시즌 평균도 다시 받지 않는다")
    void offSeasonKeepsTheCurrentSeasonRows() {
        // given
        // 지금 시즌 줄을 오래전에 받았어도 비시즌이면 다시 받지 않는다(끝난 시즌 값은 바뀌지 않는다)
        rows.add(row(2026, MLB, "2026-09-30T08:00:00Z"));
        rows.add(row(2026, "AAA", "2026-09-30T08:00:00Z"));
        rows.add(row(2026, "AA", "2026-09-30T08:00:00Z"));

        // when // then
        assertThat(refresher.refresh(false)).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("지난 시즌 평균은 새 시즌 중에도 다시 받지 않는다")
    void pastSeasonRowsAreNeverFetchedAgain() {
        // given
        // 지금 시즌이 2027로 넘어갔다. 2026 줄은 시즌 중에도 다시 받지 않고, 2027 줄만 받는다
        when(currentSeason.current()).thenReturn(2027);
        clock.set(Instant.parse("2027-04-10T05:00:00Z"));
        rows.add(row(2026, MLB, "2026-09-30T08:00:00Z"));
        rows.add(row(2026, "AAA", "2026-09-30T08:00:00Z"));
        rows.add(row(2026, "AA", "2026-09-30T08:00:00Z"));

        // when // then
        assertThat(refresher.refresh(true)).isEqualTo("리그 평균 1개");
        assertThat(asked).containsExactly("2027 1 hitting", "2027 1 pitching");

        // given
        clock.advance(Duration.ofDays(1));

        // when
        refresher.refresh(true);

        // then
        assertThat(asked).containsExactly("2027 1 hitting", "2027 1 pitching", "2027 1 hitting", "2027 1 pitching");
    }

    @Test
    @DisplayName("못 받은 단계는 건너뛰고 쉬는 시간이 지나면 그것만 다시 받는다")
    void failedCombinationIsSkippedAndRetriedAfterAWhile() {
        // given
        failing.add("2026 11 hitting");

        // when // then
        assertThat(refresher.refresh(false)).isEqualTo("리그 평균 2개, 실패 1개");
        assertThat(rows).extracting(LeagueAverage::getLevel).containsExactly(MLB, "AA");

        // given
        // 바로 다음 수집: 방금 실패한 조합은 RETRY_AFTER 동안 다시 묻지 않는다(계속 실패하는 조합을 12분마다 묻지 않게)
        failing.clear();
        asked.clear();

        // when // then
        assertThat(refresher.refresh(false)).isEmpty();
        assertThat(asked).isEmpty();

        // given
        // RETRY_AFTER 뒤: 못 받은 트리플A만 다시 묻는다
        clock.set(NOW.plus(LeagueAverageRefresher.RETRY_AFTER));

        // when // then
        assertThat(refresher.refresh(false)).isEqualTo("리그 평균 1개");
        assertThat(asked).containsExactly("2026 11 hitting", "2026 11 pitching");
        assertThat(rows).extracting(LeagueAverage::getLevel).containsExactly(MLB, "AA", "AAA");
    }

    @Test
    @DisplayName("다시 받다 막히면 저장된 평균을 그대로 둔다")
    void failedRefreshKeepsTheStoredRow() {
        // given
        clock.set(Instant.parse("2026-07-02T00:10:00Z"));
        LeagueAverage major = row(2026, MLB, "2026-07-01T05:00:00Z");
        rows.add(major);
        failing.add("2026 1 pitching");

        // when // then
        assertThat(refresher.refresh(true)).isEqualTo("리그 평균 2개, 실패 1개");
        assertThat(major.getFetchedAt()).isEqualTo(Instant.parse("2026-07-01T05:00:00Z"));
        assertThat(major.values()).isEqualTo(new LeagueAverage.Values(0.31, 0.12, 4.2));
    }

    @Test
    @DisplayName("MLB가 거절하면 앞서 받은 평균만 남기고 더 묻지 않는다")
    void blockedStopsTheRun() {
        // given
        blocked.add("2026 11 hitting");

        // when // then
        assertThatThrownBy(() -> refresher.refresh(false)).isInstanceOf(MlbBlockedException.class);
        // 앞서 받은 MLB 줄은 저장돼 있고, 뒤 조합(더블A)은 묻지 않았다
        assertThat(rows).extracting(LeagueAverage::getLevel).containsExactly(MLB);
        assertThat(asked).containsExactly("2026 1 hitting", "2026 1 pitching", "2026 11 hitting");
    }

    @Test
    @DisplayName("빈 합계는 저장하지 않고 다음 날 다시 묻는다")
    void emptyTotalsAreNotSavedAndNotAskedAgainToday() {
        // given
        // 개막 전·샘플 모드처럼 합계가 비어 오는 조합
        empty.add("2026 12");

        // when // then
        assertThat(refresher.refresh(false)).isEqualTo("리그 평균 2개");
        assertThat(rows).extracting(LeagueAverage::getLevel).containsExactly(MLB, "AAA");

        // given
        // 같은 날(UTC)에는 다시 묻지 않는다
        asked.clear();
        clock.advance(Duration.ofHours(5));

        // when // then
        assertThat(refresher.refresh(false)).isEmpty();
        assertThat(asked).isEmpty();

        // given
        // 다음 날 다시 묻고, 값이 나오면 저장한다
        empty.clear();
        clock.advance(Duration.ofDays(1));

        // when // then
        assertThat(refresher.refresh(false)).isEqualTo("리그 평균 1개");
        assertThat(asked).containsExactly("2026 12 hitting", "2026 12 pitching");
        assertThat(rows).extracting(LeagueAverage::getLevel).containsExactly(MLB, "AAA", "AA");
    }

    @Test
    @DisplayName("샘플 데이터에는 팀 합계가 없어 리그 평균을 저장하지 않는다")
    void sampleClientHasNoTotalsSoNothingIsSaved() {
        // given
        // 샘플 클라이언트는 팀 합계를 주지 않는다. 저장하지 않으므로 화면 계산은 표준값을 쓴다(샘플 모드의 테스트 기대값이 그대로다)
        SampleMlbClient sample = new SampleMlbClient(SampleMlbClient.Data.empty());
        refresher = new LeagueAverageRefresher(sample, averages, appearances, minorGameLogs, currentSeason, clock);

        // when // then
        assertThat(refresher.refresh(true)).isEmpty();
        assertThat(rows).isEmpty();
        assertThat(sample.requestCount()).isZero();
    }

    private static LeagueAverageRefresher.Key key(int season, String level) {
        return new LeagueAverageRefresher.Key(season, level);
    }

    private static LeagueAverage row(int season, String level, String fetchedAt) {
        LeagueAverage row = new LeagueAverage(season, level);
        row.update(new LeagueAverage.Values(0.31, 0.12, 4.2), Instant.parse(fetchedAt));
        return row;
    }

    /** 팀 합계를 주는 가짜 클라이언트. 물은 것을 적고, failing은 5xx, blocked는 403, empty는 빈 응답을 흉내 낸다 */
    private MlbClient client() {
        return new SampleMlbClient(SampleMlbClient.Data.empty()) {
            @Override
            public List<MlbGameLogSplit> getTeamSeasonTotals(int sportId, Group group, int season) {
                String call = season + " " + sportId + " " + group.value();
                asked.add(call);
                if (blocked.contains(call)) {
                    throw new MlbBlockedException("MLB refused (403)", null);
                }
                if (failing.contains(call)) {
                    throw new MlbApiException("MLB request failed: teams/stats (500)", null);
                }
                if (empty.contains(season + " " + sportId)) {
                    return List.of();
                }
                // 단계마다 값이 다르게: 득점·자책에 sportId를 더한다
                return group == Group.HITTING
                        ? List.of(team(hitting(480, 120, 25, 3, 15, 50, 6, 4, 550, 70 + sportId)))
                        : List.of(team(pitching(1350, 250 + sportId)));
            }
        };
    }

    private static MlbGameLogSplit team(MlbStat stat) {
        return new MlbGameLogSplit("2026", null, null, null, null, null, null, null, null, stat, null);
    }

    private static MlbStat hitting(int atBats, int hits, int doubles, int triples, int homeRuns, int walks,
                                   int hitByPitch, int sacFlies, int plateAppearances, int runs) {
        return new MlbStat(162, null, atBats, runs, hits, doubles, triples, homeRuns, null, walks, null, hitByPitch,
                null, sacFlies, plateAppearances, null, null, null, null, null, null, null, null, null, null);
    }

    private static MlbStat pitching(int outs, int earnedRuns) {
        return new MlbStat(162, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                outs, null, earnedRuns, null, null, null, null, null, null);
    }
}
