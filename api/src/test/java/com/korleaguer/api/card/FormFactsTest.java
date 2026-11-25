package com.korleaguer.api.card;

import com.korleaguer.api.season.GameStat;

import com.korleaguer.api.game.GameLine;

import com.korleaguer.domain.Lineup;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 한 줄 소식·출전 줄 규칙(이슈 #33). 판정 단어 없이 사실만 쓴다. */
class FormFactsTest {

    private static final LocalDate START = LocalDate.of(2026, 6, 1);

    @Test
    @DisplayName("볼넷만 고른 경기가 끼어도 5경기 연속 안타로 알린다")
    void hitStreakOfFiveOrMore() {
        // given
        // 볼넷만 고른 경기(0타수)는 건너뛰고 이어서 센다
        List<GameStat> games = hitter(new int[][]{{4, 0}, {4, 1}, {3, 2}, {0, 0}, {4, 1}, {5, 3}, {4, 1}});

        // when
        CardFact fact = FormFacts.hitter(games, games, "", CardStatuses.Start.OTHER);

        // then
        assertThat(fact.tone()).isEqualTo(FormFacts.HOT);
        assertThat(fact.text()).isEqualTo("5경기 연속 안타");
    }

    @Test
    @DisplayName("대타 1타석 무안타를 빼고 4경기 연속 무안타로 알린다")
    void hitlessRunCountsOnlyGamesWithTwoPlateAppearances() {
        // given
        // 대타 1타석 무안타는 세지 않는다
        List<GameStat> games = hitter(new int[][]{{4, 2}, {4, 0}, {1, 0}, {3, 0}, {4, 0}, {4, 0}});

        // when
        CardFact fact = FormFacts.hitter(games, games, "", CardStatuses.Start.OTHER);

        // then
        assertThat(fact.tone()).isEqualTo(FormFacts.COLD);
        assertThat(fact.text()).isEqualTo("4경기 연속 무안타");
    }

    @Test
    @DisplayName("최근 10경기 홈런 수를 알리고 복귀한 지 얼마 안 되면 복귀 후로 센다")
    void recentHomeRunsAndMultiHitGamesUseTheStint() {
        // given
        List<GameStat> season = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            season.add(stat(i, 4, i % 3 == 0 ? 2 : 0, i == 10 || i == 11 ? 1 : 0));
        }
        // 마지막 경기가 무안타라 연속 기록은 없고, 최근 10경기 2홈런
        season.add(stat(12, 4, 0, 0));

        // when
        CardFact fact = FormFacts.hitter(season, season, "", CardStatuses.Start.OTHER);

        // then
        assertThat(fact.text()).isEqualTo("최근 10경기 2홈런");

        // given
        // 복귀 뒤 6경기뿐이면 "복귀 후 6경기"
        List<GameStat> stint = season.subList(7, 13);

        // when
        CardFact after = FormFacts.hitter(season, stint, "트리플A ", CardStatuses.Start.RETURNED);

        // then
        assertThat(after.text()).isEqualTo("트리플A 복귀 후 6경기 2홈런");
    }

    @Test
    @DisplayName("강등 뒤 6경기에서 멀티히트가 3번이면 그 횟수를 알린다")
    void multiHitGames() {
        // given
        List<GameStat> games = hitter(new int[][]{{4, 2}, {4, 0}, {4, 3}, {4, 0}, {4, 2}, {4, 0}});

        // when
        CardFact fact = FormFacts.hitter(games, games, "", CardStatuses.Start.SENT_DOWN);

        // then
        assertThat(fact.text()).isEqualTo("강등 후 6경기 멀티히트 3번");
    }

    @Test
    @DisplayName("타자 기준에 하나씩 모자라면 소식이 없다")
    void justBelowEveryHitterBarThereIsNoFact() {
        // when // then
        // 4경기 연속 안타(기준 5)
        assertThat(hitterFact(hitter(new int[][]{{4, 0}, {4, 1}, {4, 1}, {4, 1}, {4, 1}}))).isNull();
        // 3경기 연속 무안타(기준 4)
        assertThat(hitterFact(hitter(new int[][]{{4, 1}, {4, 0}, {4, 0}, {4, 0}}))).isNull();
        // 멀티히트 2번(기준 3)
        assertThat(hitterFact(hitter(new int[][]{{4, 2}, {4, 0}, {4, 2}, {4, 0}}))).isNull();
        // 홈런 1개(기준 2)
        assertThat(hitterFact(List.of(stat(0, 4, 1, 1), stat(1, 4, 0, 0)))).isNull();
    }

    @Test
    @DisplayName("2타석 무안타 경기는 연속 무안타에 세고 1타석은 세지 않는다")
    void aGameWithTwoPlateAppearancesCountsInTheHitlessRun() {
        // when // then
        // 2타석 무안타는 세고 1타석 무안타는 세지 않는다
        assertThat(hitterFact(hitter(new int[][]{{4, 1}, {4, 0}, {2, 0}, {4, 0}, {4, 0}})).text())
                .isEqualTo("4경기 연속 무안타");
        assertThat(hitterFact(hitter(new int[][]{{4, 1}, {4, 0}, {1, 0}, {4, 0}, {4, 0}}))).isNull();
    }

    @Test
    @DisplayName("대타로 안타를 치면 연속 무안타가 끝난다")
    void aPinchHitEndsTheHitlessRun() {
        // when // then
        // 어제 대타 1타석 안타: 앞의 4경기 무안타가 끝났다
        assertThat(hitterFact(hitter(new int[][]{{4, 1}, {4, 0}, {4, 0}, {4, 0}, {4, 0}, {1, 1}}))).isNull();
        // 무안타 사이에 낀 대타 안타: 그 뒤 경기부터 다시 센다
        assertThat(hitterFact(hitter(new int[][]{{4, 0}, {4, 0}, {1, 1}, {4, 0}, {4, 0}, {4, 0}}))).isNull();
        assertThat(hitterFact(hitter(new int[][]{{4, 0}, {1, 1}, {4, 0}, {4, 0}, {4, 0}, {4, 0}})).text())
                .isEqualTo("4경기 연속 무안타");
    }

    @Test
    @DisplayName("11경기 전 홈런은 최근 10경기에 넣지 않는다")
    void onlyTheLastTenGamesCountAsRecent() {
        // given
        // 11경기: 홈런 둘 중 하나가 11경기 전이면 최근 10경기는 홈런 1개
        List<GameStat> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(stat(i, 4, i % 2 == 0 && i < 10 ? 1 : 0, i == 0 || i == 4 ? 1 : 0));
        }

        // when // then
        assertThat(hitterFact(eleven)).isNull();
        assertThat(hitterFact(eleven.subList(0, 10)).text()).isEqualTo("최근 10경기 2홈런");
    }

    @Test
    @DisplayName("투수 기준에 모자라면 소식이 없고 4번 등판에 3번 실점하면 알린다")
    void justBelowEveryPitcherBarThereIsNoFact() {
        // when // then
        // 2번 연속 무실점(기준 3)
        assertThat(pitcherFact(pitcher(1, 0, 0))).isNull();
        // 5번 중 3번 무실점(기준 4), 2번 실점(기준 3)
        assertThat(pitcherFact(pitcher(0, 1, 0, 1, 0))).isNull();
        // 3번 등판은 모두 실점해도 최근 등판 소식이 없고(4번부터) 4번이면 있다
        assertThat(pitcherFact(pitcher(1, 1, 1))).isNull();
        assertThat(pitcherFact(pitcher(0, 1, 1, 1)).text()).isEqualTo("최근 4번 등판 중 3번 실점");
    }

    @Test
    @DisplayName("시즌 최장 기록은 타자 5경기 안타, 투수 3번 무실점부터 알린다")
    void seasonBestStartsAtFiveGamesForHittersAndThreeOutingsForPitchers() {
        // when // then
        assertThat(FormFacts.seasonBest(List.of(hitter(new int[][]{{4, 1}, {4, 1}, {4, 1}, {4, 1}, {4, 0}})), false)).isNull();
        assertThat(FormFacts.seasonBest(List.of(hitter(new int[][]{{4, 1}, {4, 1}, {4, 1}, {4, 1}, {4, 1}})), false).text())
                .isEqualTo("시즌 최장 5경기 연속 안타");
        assertThat(FormFacts.seasonBest(List.of(pitcher(0, 0, 1)), true)).isNull();
        assertThat(FormFacts.seasonBest(List.of(pitcher(0, 0, 0)), true).text()).isEqualTo("시즌 최장 3번 연속 무실점 등판");
    }

    @Test
    @DisplayName("안타와 무안타를 번갈아 치면 소식이 없다")
    void nothingNotableMeansNoFact() {
        // given
        List<GameStat> games = hitter(new int[][]{{4, 1}, {4, 0}, {4, 1}, {4, 0}});

        // when // then
        assertThat(FormFacts.hitter(games, games, "", CardStatuses.Start.OTHER)).isNull();
    }

    @Test
    @DisplayName("투수는 연속 무실점이나 최근 등판의 무실점, 실점 횟수를 알린다")
    void pitcherScorelessRunAndRecentAppearances() {
        // given
        List<GameStat> streak = pitcher(1, 0, 0, 0);

        // when // then
        assertThat(FormFacts.pitcher(streak, streak, "", CardStatuses.Start.OTHER).text())
                .isEqualTo("3번 연속 무실점 등판");

        // given
        List<GameStat> clean = pitcher(0, 0, 0, 2, 0);

        // when
        CardFact hot = FormFacts.pitcher(clean, clean, "트리플A ", CardStatuses.Start.OTHER);

        // then
        assertThat(hot.tone()).isEqualTo(FormFacts.HOT);
        assertThat(hot.text()).isEqualTo("트리플A 최근 5번 등판 중 4번 무실점");

        // given
        List<GameStat> rough = pitcher(1, 0, 2, 3, 0);

        // when
        CardFact cold = FormFacts.pitcher(rough, rough, "", CardStatuses.Start.OTHER);

        // then
        assertThat(cold.tone()).isEqualTo(FormFacts.COLD);
        assertThat(cold.text()).isEqualTo("최근 5번 등판 중 3번 실점");

        // given
        // 등판이 4번이 안 되면 최근 등판 소식은 없다
        List<GameStat> few = pitcher(1, 2, 0);

        // when // then
        assertThat(FormFacts.pitcher(few, few, "", CardStatuses.Start.OTHER)).isNull();
    }

    @Test
    @DisplayName("시즌 최장 연속 안타는 메이저와 마이너 가운데 긴 쪽을 알린다")
    void seasonBestTakesTheLongerOfMajorsAndMinors() {
        // given
        List<GameStat> majors = hitter(new int[][]{{4, 1}, {4, 1}, {4, 0}});
        List<GameStat> minors = hitter(new int[][]{{4, 1}, {4, 1}, {4, 1}, {4, 1}, {4, 1}, {4, 1}, {4, 0}});

        // when // then
        assertThat(FormFacts.seasonBest(List.of(majors, minors), false).text()).isEqualTo("시즌 최장 6경기 연속 안타");
        assertThat(FormFacts.seasonBest(List.of(majors), false)).isNull();
    }

    @Test
    @DisplayName("타자 출전 줄에 선발, 교체, 결장을 가르고 10경기면 콜업 후 표시를 뺀다")
    void hitterPlayMarksStartsSubsAndAbsences() {
        // given
        List<GameLine> lines = List.of(line(true, Lineup.STARTER), line(true, Lineup.SUB), line(false, null),
                line(true, null));

        // when
        CardPlay play = FormFacts.hitterPlay(lines, CardStatuses.Start.CALLED_UP);

        // then
        assertThat(play.recent()).containsExactly("STARTER", "SUB", "ABSENT", "PLAYED");
        assertThat(play.after()).isEqualTo("CALLED_UP");

        // given
        List<GameLine> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(line(true, Lineup.STARTER));
        }

        // when // then
        assertThat(FormFacts.hitterPlay(ten, CardStatuses.Start.CALLED_UP).after()).isNull();
    }

    @Test
    @DisplayName("투수는 최근 14일 등판 수와 마지막 등판 뒤 지난 날을 센다")
    void pitcherPlayCountsTheLastFourteenDays() {
        // given
        LocalDate today = LocalDate.of(2026, 6, 20);
        List<GameStat> games = List.of(
                new GameStat(LocalDate.of(2026, 6, 6), false, 0, 0, 0, 0, true, 0),
                new GameStat(LocalDate.of(2026, 6, 7), false, 0, 0, 0, 0, true, 0),
                new GameStat(LocalDate.of(2026, 6, 18), false, 0, 0, 0, 0, true, 1));

        // when
        CardPlay play = FormFacts.pitcherPlay(games, today);

        // then
        assertThat(play.appearances14()).isEqualTo(2);
        assertThat(play.daysSinceLast()).isEqualTo(2);
    }

    private static CardFact hitterFact(List<GameStat> games) {
        return FormFacts.hitter(games, games, "", CardStatuses.Start.OTHER);
    }

    private static CardFact pitcherFact(List<GameStat> games) {
        return FormFacts.pitcher(games, games, "", CardStatuses.Start.OTHER);
    }

    /** {타수, 안타} 목록 → 하루씩 이어진 타자 경기(타석 = 타수, 볼넷만 고른 경기는 0타수 1타석) */
    private static List<GameStat> hitter(int[][] games) {
        List<GameStat> list = new ArrayList<>();
        for (int i = 0; i < games.length; i++) {
            list.add(stat(i, games[i][0], games[i][1], 0));
        }
        return list;
    }

    private static GameStat stat(int day, int atBats, int hits, int homeRuns) {
        int plateAppearances = atBats == 0 ? 1 : atBats;
        return new GameStat(START.plusDays(day), true, atBats, hits, homeRuns, plateAppearances, false, null);
    }

    /** 실점 목록 → 하루씩 이어진 등판 */
    private static List<GameStat> pitcher(int... runs) {
        List<GameStat> list = new ArrayList<>();
        for (int i = 0; i < runs.length; i++) {
            list.add(new GameStat(START.plusDays(i), false, 0, 0, 0, 0, true, runs[i]));
        }
        return list;
    }

    private static GameLine line(boolean played, Lineup lineup) {
        return new GameLine(1, "선수", null, Role.HITTER, 1, OffsetDateTime.parse("2026-06-01T08:00:00+09:00"), "팀",
                null, "상대", null, true, 1, 0, "W", "final", played, played ? "4타수 1안타" : "결장", null, null, lineup,
                SeasonType.REGULAR, null);
    }
}
