package com.korleaguer.api.card;

import com.korleaguer.api.season.GameStat;

import com.korleaguer.api.game.GameLine;

import com.korleaguer.domain.Lineup;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
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
    void hitStreakOfFiveOrMore() {
        // 볼넷만 고른 경기(0타수)는 건너뛰고 이어서 센다
        List<GameStat> games = hitter(new int[][]{{4, 0}, {4, 1}, {3, 2}, {0, 0}, {4, 1}, {5, 3}, {4, 1}});
        CardFact fact = FormFacts.hitter(games, games, "", CardStatuses.Start.OTHER);
        assertThat(fact.tone()).isEqualTo(FormFacts.HOT);
        assertThat(fact.text()).isEqualTo("5경기 연속 안타");
    }

    @Test
    void hitlessRunCountsOnlyGamesWithTwoPlateAppearances() {
        // 대타 1타석 무안타는 세지 않는다
        List<GameStat> games = hitter(new int[][]{{4, 2}, {4, 0}, {1, 0}, {3, 0}, {4, 0}, {4, 0}});
        CardFact fact = FormFacts.hitter(games, games, "", CardStatuses.Start.OTHER);
        assertThat(fact.tone()).isEqualTo(FormFacts.COLD);
        assertThat(fact.text()).isEqualTo("4경기 연속 무안타");
    }

    @Test
    void recentHomeRunsAndMultiHitGamesUseTheStint() {
        List<GameStat> season = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            season.add(stat(i, 4, i % 3 == 0 ? 2 : 0, i == 10 || i == 11 ? 1 : 0));
        }
        // 마지막 경기가 무안타라 연속 기록은 없고, 최근 10경기 2홈런
        season.add(stat(12, 4, 0, 0));
        CardFact fact = FormFacts.hitter(season, season, "", CardStatuses.Start.OTHER);
        assertThat(fact.text()).isEqualTo("최근 10경기 2홈런");

        // 복귀 뒤 6경기뿐이면 "복귀 후 6경기"
        List<GameStat> stint = season.subList(7, 13);
        CardFact after = FormFacts.hitter(season, stint, "트리플A ", CardStatuses.Start.RETURNED);
        assertThat(after.text()).isEqualTo("트리플A 복귀 후 6경기 2홈런");
    }

    @Test
    void multiHitGames() {
        List<GameStat> games = hitter(new int[][]{{4, 2}, {4, 0}, {4, 3}, {4, 0}, {4, 2}, {4, 0}});
        CardFact fact = FormFacts.hitter(games, games, "", CardStatuses.Start.SENT_DOWN);
        assertThat(fact.text()).isEqualTo("강등 후 6경기 멀티히트 3번");
    }

    @Test
    void nothingNotableMeansNoFact() {
        List<GameStat> games = hitter(new int[][]{{4, 1}, {4, 0}, {4, 1}, {4, 0}});
        assertThat(FormFacts.hitter(games, games, "", CardStatuses.Start.OTHER)).isNull();
    }

    @Test
    void pitcherScorelessRunAndRecentAppearances() {
        List<GameStat> streak = pitcher(1, 0, 0, 0);
        assertThat(FormFacts.pitcher(streak, streak, "", CardStatuses.Start.OTHER).text())
                .isEqualTo("3번 연속 무실점 등판");

        List<GameStat> clean = pitcher(0, 0, 0, 2, 0);
        CardFact hot = FormFacts.pitcher(clean, clean, "트리플A ", CardStatuses.Start.OTHER);
        assertThat(hot.tone()).isEqualTo(FormFacts.HOT);
        assertThat(hot.text()).isEqualTo("트리플A 최근 5번 등판 중 4번 무실점");

        List<GameStat> rough = pitcher(1, 0, 2, 3, 0);
        CardFact cold = FormFacts.pitcher(rough, rough, "", CardStatuses.Start.OTHER);
        assertThat(cold.tone()).isEqualTo(FormFacts.COLD);
        assertThat(cold.text()).isEqualTo("최근 5번 등판 중 3번 실점");

        // 등판이 4번이 안 되면 최근 등판 소식은 없다
        List<GameStat> few = pitcher(1, 2, 0);
        assertThat(FormFacts.pitcher(few, few, "", CardStatuses.Start.OTHER)).isNull();
    }

    @Test
    void seasonBestTakesTheLongerOfMajorsAndMinors() {
        List<GameStat> majors = hitter(new int[][]{{4, 1}, {4, 1}, {4, 0}});
        List<GameStat> minors = hitter(new int[][]{{4, 1}, {4, 1}, {4, 1}, {4, 1}, {4, 1}, {4, 1}, {4, 0}});
        assertThat(FormFacts.seasonBest(List.of(majors, minors), false).text()).isEqualTo("시즌 최장 6경기 연속 안타");
        assertThat(FormFacts.seasonBest(List.of(majors), false)).isNull();
    }

    @Test
    void hitterPlayMarksStartsSubsAndAbsences() {
        List<GameLine> lines = List.of(line(true, Lineup.STARTER), line(true, Lineup.SUB), line(false, null),
                line(true, null));
        CardPlay play = FormFacts.hitterPlay(lines, CardStatuses.Start.CALLED_UP);
        assertThat(play.recent()).containsExactly("STARTER", "SUB", "ABSENT", "PLAYED");
        assertThat(play.after()).isEqualTo("CALLED_UP");

        List<GameLine> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(line(true, Lineup.STARTER));
        }
        assertThat(FormFacts.hitterPlay(ten, CardStatuses.Start.CALLED_UP).after()).isNull();
    }

    @Test
    void pitcherPlayCountsTheLastFourteenDays() {
        LocalDate today = LocalDate.of(2026, 6, 20);
        List<GameStat> games = List.of(
                new GameStat(LocalDate.of(2026, 6, 6), false, 0, 0, 0, 0, true, 0),
                new GameStat(LocalDate.of(2026, 6, 7), false, 0, 0, 0, 0, true, 0),
                new GameStat(LocalDate.of(2026, 6, 18), false, 0, 0, 0, 0, true, 1));
        CardPlay play = FormFacts.pitcherPlay(games, today);
        assertThat(play.appearances14()).isEqualTo(2);
        assertThat(play.daysSinceLast()).isEqualTo(2);
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
