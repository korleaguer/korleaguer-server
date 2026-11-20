package com.korleaguer.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 화제 문구 규칙. 경기 숫자는 2026 실제 날짜의 모양(이정후 6/24·8/4, 송성문 7/2, 화제 없는 6/22)을 따랐다. */
class GameBuzzRulesTest {

    private static long nextId = 1;

    /** 타격 경기: 타수·안타·홈런·타점·볼넷·도루·루타 */
    private static GameBuzzRules.Game bat(int ab, int h, int hr, int rbi, int bb, int sb, int tb) {
        return new GameBuzzRules.Game(nextId++, true, ab, h, hr, rbi, bb, sb, tb, false);
    }

    private static GameBuzzRules.Game plain() {
        return bat(4, 1, 0, 0, 0, 0, 1);
    }

    private static GameBuzzRules.Game hitless() {
        return bat(4, 0, 0, 0, 0, 0, 0);
    }

    private static List<GameBuzzRules.Game> season(int games, GameBuzzRules.Game last) {
        List<GameBuzzRules.Game> list = new ArrayList<>();
        for (int i = 0; i < games; i++) {
            list.add(i % 2 == 0 ? plain() : hitless());
        }
        list.add(last);
        return list;
    }

    private static Optional<GameBuzz> find(List<GameBuzzRules.Game> games, boolean pitcher, boolean debut) {
        return GameBuzzRules.find(1, games, pitcher, debut);
    }

    @Test
    void debutSeasonFirstHomeRunIsMajorFirst() {
        // 송성문 7/2: 데뷔 시즌, 앞 경기에 홈런 없음
        Optional<GameBuzz> buzz = find(season(30, bat(4, 2, 1, 1, 0, 0, 5)), false, true);
        assertThat(buzz).get().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(GameBuzz.FIRST);
            assertThat(b.text()).isEqualTo("메이저 첫 홈런");
            assertThat(b.stat()).isEqualTo("homeRuns");
        });
    }

    @Test
    void veteranFirstHomeRunIsSeasonFirst() {
        assertThat(find(season(10, bat(4, 1, 1, 1, 0, 0, 4)), false, false)).get()
                .extracting(GameBuzz::text).isEqualTo("올 시즌 첫 홈런");
    }

    @Test
    void secondHomeRunHasNoFirstBuzz() {
        List<GameBuzzRules.Game> games = new ArrayList<>(season(5, bat(4, 1, 1, 1, 0, 0, 4)));
        games.add(bat(4, 1, 1, 1, 0, 0, 4));
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    void firstHitOnlyForDebutSeason() {
        List<GameBuzzRules.Game> games = new ArrayList<>(List.of(hitless(), hitless(), bat(4, 1, 0, 1, 0, 1, 1)));
        assertThat(find(games, false, true)).get().satisfies(b -> {
            assertThat(b.text()).isEqualTo("메이저 첫 안타");
            assertThat(b.stat()).isEqualTo("hits");
        });
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    void pitcherFirstOutingOnlyForDebutSeason() {
        GameBuzzRules.Game outing = new GameBuzzRules.Game(1, false, 0, 0, 0, 0, 0, 0, 0, true);
        assertThat(find(List.of(outing), true, true)).get().extracting(GameBuzz::text).isEqualTo("메이저 첫 등판");
        assertThat(find(List.of(outing), true, false)).isEmpty();
        assertThat(find(List.of(outing, new GameBuzzRules.Game(2, false, 0, 0, 0, 0, 0, 0, 0, true)), true, true))
                .isEmpty();
    }

    @Test
    void hitStreakOnlyOnMilestones() {
        for (int streak = 1; streak <= 11; streak++) {
            List<GameBuzzRules.Game> games = new ArrayList<>(List.of(hitless()));
            for (int i = 0; i < streak; i++) {
                games.add(plain());
            }
            Optional<GameBuzz> buzz = find(games, false, false);
            if (streak == 5 || streak == 10) {
                assertThat(buzz).get().extracting(GameBuzz::text).isEqualTo(streak + "경기 연속 안타");
                assertThat(buzz.get().stat()).isNull();
            } else {
                assertThat(buzz).as("%d경기", streak).isEmpty();
            }
        }
    }

    @Test
    void walkOnlyGameDoesNotBreakStreakButCannotCarryIt() {
        List<GameBuzzRules.Game> games = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            games.add(plain());
        }
        games.add(bat(0, 0, 0, 0, 2, 0, 0));
        games.add(plain());
        assertThat(GameBuzzRules.hitStreak(games)).isEqualTo(5);
        // 안타 없는 마지막 경기에는 문구가 없다
        games.add(bat(0, 0, 0, 0, 1, 0, 0));
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    void seasonBestNeedsTenGamesAndBeatingAllBefore() {
        // 이정후 8/4: 2홈런 3안타 3타점 도루 1 = 루타 9 + 타점 3 + 도루 1 = 13점
        GameBuzzRules.Game big = bat(5, 3, 2, 3, 0, 1, 9);
        // 앞 홈런이 이미 있어야 '첫 홈런'이 아니다
        List<GameBuzzRules.Game> games = new ArrayList<>(List.of(bat(4, 1, 1, 1, 0, 0, 4)));
        games.addAll(season(12, big));
        assertThat(find(games, false, false)).get().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(GameBuzz.SEASON_BEST);
            assertThat(b.text()).isEqualTo("올 시즌 최고 경기");
        });
        // 9경기뿐이면 아직 비교하지 않는다
        List<GameBuzzRules.Game> early = new ArrayList<>(List.of(bat(4, 1, 1, 1, 0, 0, 4)));
        early.addAll(season(7, big));
        assertThat(find(early, false, false)).isEmpty();
        // 앞에 더 큰 경기가 있으면 아니다
        List<GameBuzzRules.Game> beaten = new ArrayList<>(List.of(bat(5, 4, 2, 4, 0, 0, 10)));
        beaten.addAll(season(12, big));
        assertThat(find(beaten, false, false)).isEmpty();
    }

    @Test
    void weakSeasonBestIsNotBuzz() {
        // 김하성처럼 시즌 최고가 4점이면 화제로 말하지 않는다
        List<GameBuzzRules.Game> games = new ArrayList<>(List.of(bat(4, 1, 1, 1, 0, 0, 4)));
        games.addAll(season(12, bat(4, 1, 1, 1, 0, 0, 4)));
        // 마지막 경기 점수 5 = 루타 4 + 타점 1이라 6 미만이고, 홈런도 처음이 아니다
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    void ordinaryDayHasNoBuzz() {
        // 6/22 같은 날: 1안타, 연속도 최고도 처음도 아님
        List<GameBuzzRules.Game> games = new ArrayList<>(List.of(bat(4, 1, 1, 1, 0, 0, 4)));
        games.addAll(season(20, bat(4, 2, 0, 1, 0, 0, 2)));
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    void firstBeatsStreak() {
        // 연속 5경기째이면서 올 시즌 첫 홈런이면 처음이 먼저
        List<GameBuzzRules.Game> games = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            games.add(plain());
        }
        games.add(bat(4, 1, 1, 1, 0, 0, 4));
        assertThat(find(games, false, false)).get().extracting(GameBuzz::kind).isEqualTo(GameBuzz.FIRST);
    }

    @Test
    void passedTextNamesTheSeniorAndCount() {
        // 이정후 6/24: 한국인 통산 홈런, 이대호(14개) 넘음
        RecordsResponse.Passed passed = new RecordsResponse.Passed(1L, 808982, "이정후", null, "homeRuns", "홈런",
                "홈런", java.time.LocalDate.of(2026, 6, 24), "이대호", 14);
        assertThat(GameBuzzFinder.passedText(passed)).isEqualTo("한국인 통산 홈런 이대호(14개) 넘음");
        RecordsResponse.Passed games = new RecordsResponse.Passed(4L, 1, "송성문", null, "games", "경기", "경기",
                java.time.LocalDate.of(2026, 9, 13), "박효준", 68);
        assertThat(GameBuzzFinder.passedText(games)).isEqualTo("한국인 통산 경기 박효준(68경기) 넘음");
        RecordsResponse.Passed saves = new RecordsResponse.Passed(5L, 2, "고우석", null, "saves", "세이브", "세이브",
                java.time.LocalDate.of(2026, 8, 1), "오승환", 42);
        assertThat(GameBuzzFinder.passedText(saves)).isEqualTo("한국인 통산 세이브 오승환(42세이브) 넘음");
    }
}
