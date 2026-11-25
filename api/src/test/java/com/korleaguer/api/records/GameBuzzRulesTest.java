package com.korleaguer.api.records;

import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("데뷔 시즌 첫 홈런이면 메이저 첫 홈런이라고 알린다")
    void debutSeasonFirstHomeRunIsMajorFirst() {
        // when
        // 송성문 7/2: 데뷔 시즌, 앞 경기에 홈런 없음
        Optional<GameBuzz> buzz = find(season(30, bat(4, 2, 1, 1, 0, 0, 5)), false, true);

        // then
        assertThat(buzz).get().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(GameBuzz.FIRST);
            assertThat(b.text()).isEqualTo("메이저 첫 홈런");
            assertThat(b.stat()).isEqualTo("homeRuns");
        });
    }

    @Test
    @DisplayName("데뷔 시즌이 아니면 그해 첫 홈런을 올 시즌 첫 홈런이라고 알린다")
    void veteranFirstHomeRunIsSeasonFirst() {
        // when // then
        assertThat(find(season(10, bat(4, 1, 1, 1, 0, 0, 4)), false, false)).get()
                .extracting(GameBuzz::text).isEqualTo("올 시즌 첫 홈런");
    }

    @Test
    @DisplayName("시즌 두 번째 홈런에는 화제 문구가 없다")
    void secondHomeRunHasNoFirstBuzz() {
        // given
        List<GameBuzzRules.Game> games = new ArrayList<>(season(5, bat(4, 1, 1, 1, 0, 0, 4)));
        games.add(bat(4, 1, 1, 1, 0, 0, 4));

        // when // then
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    @DisplayName("첫 안타는 데뷔 시즌에만 메이저 첫 안타라고 알린다")
    void firstHitOnlyForDebutSeason() {
        // given
        List<GameBuzzRules.Game> games = new ArrayList<>(List.of(hitless(), hitless(), bat(4, 1, 0, 1, 0, 1, 1)));

        // when // then
        assertThat(find(games, false, true)).get().satisfies(b -> {
            assertThat(b.text()).isEqualTo("메이저 첫 안타");
            assertThat(b.stat()).isEqualTo("hits");
        });
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    @DisplayName("첫 등판은 데뷔 시즌에만 알리고 두 번째 등판에는 문구가 없다")
    void pitcherFirstOutingOnlyForDebutSeason() {
        // given
        GameBuzzRules.Game outing = new GameBuzzRules.Game(1, false, 0, 0, 0, 0, 0, 0, 0, true);

        // when // then
        assertThat(find(List.of(outing), true, true)).get().extracting(GameBuzz::text).isEqualTo("메이저 첫 등판");
        assertThat(find(List.of(outing), true, false)).isEmpty();
        assertThat(find(List.of(outing, new GameBuzzRules.Game(2, false, 0, 0, 0, 0, 0, 0, 0, true)), true, true))
                .isEmpty();
    }

    @Test
    @DisplayName("1~11경기 연속 안타 가운데 5경기와 10경기째에만 알린다")
    void hitStreakOnlyOnMilestones() {
        for (int streak = 1; streak <= 11; streak++) {
            // given
            List<GameBuzzRules.Game> games = new ArrayList<>(List.of(hitless()));
            for (int i = 0; i < streak; i++) {
                games.add(plain());
            }

            // when
            Optional<GameBuzz> buzz = find(games, false, false);

            // then
            if (streak == 5 || streak == 10) {
                assertThat(buzz).get().extracting(GameBuzz::text).isEqualTo(streak + "경기 연속 안타");
                assertThat(buzz.get().stat()).isNull();
            } else {
                assertThat(buzz).as("%d경기", streak).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("볼넷만 고른 경기는 연속 안타를 끊지 않지만 그날은 문구가 없다")
    void walkOnlyGameDoesNotBreakStreakButCannotCarryIt() {
        // given
        List<GameBuzzRules.Game> games = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            games.add(plain());
        }
        games.add(bat(0, 0, 0, 0, 2, 0, 0));
        games.add(plain());

        // when // then
        assertThat(GameBuzzRules.hitStreak(games)).isEqualTo(5);

        // given
        // 안타 없는 마지막 경기에는 문구가 없다
        games.add(bat(0, 0, 0, 0, 1, 0, 0));

        // when // then
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    @DisplayName("앞선 경기가 10개 이상이고 활약이 모두보다 커야 시즌 최고로 알린다")
    void seasonBestNeedsTenGamesAndBeatingAllBefore() {
        // given
        // 이정후 8/4: 2홈런 3안타 3타점 도루 1 = 루타 9 + 타점 3 + 도루 1 = 13점
        GameBuzzRules.Game big = bat(5, 3, 2, 3, 0, 1, 9);
        // 앞 홈런이 이미 있어야 '첫 홈런'이 아니다
        List<GameBuzzRules.Game> games = new ArrayList<>(List.of(bat(4, 1, 1, 1, 0, 0, 4)));
        games.addAll(season(12, big));

        // when // then
        assertThat(find(games, false, false)).get().satisfies(b -> {
            assertThat(b.kind()).isEqualTo(GameBuzz.SEASON_BEST);
            assertThat(b.text()).isEqualTo("올 시즌 최고 경기");
        });

        // given
        // 9경기뿐이면 아직 비교하지 않는다
        List<GameBuzzRules.Game> early = new ArrayList<>(List.of(bat(4, 1, 1, 1, 0, 0, 4)));
        early.addAll(season(8, big));

        // when // then
        assertThat(find(early, false, false)).isEmpty();

        // given
        // 앞에 더 큰 경기가 있으면 아니다
        List<GameBuzzRules.Game> beaten = new ArrayList<>(List.of(bat(5, 4, 2, 4, 0, 0, 10)));
        beaten.addAll(season(12, big));

        // when // then
        assertThat(find(beaten, false, false)).isEmpty();
    }

    @Test
    @DisplayName("활약 점수(루타·타점·도루·볼넷)가 5점이면 시즌 최고로 알리지 않는다")
    void weakSeasonBestIsNotBuzz() {
        // given
        // 시즌 최고가 5점뿐이면 화제로 말하지 않는다
        List<GameBuzzRules.Game> games = new ArrayList<>(List.of(bat(4, 1, 1, 1, 0, 0, 4)));
        games.addAll(season(12, bat(4, 1, 1, 1, 0, 0, 4)));

        // when // then
        // 마지막 경기 점수 5 = 루타 4 + 타점 1이라 6 미만이고, 홈런도 처음이 아니다
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    @DisplayName("활약 점수가 6점 이상이고 앞선 10경기를 모두 넘어야 시즌 최고다")
    void seasonBestChangesExactlyAtTheBars() {
        // given
        // 홈런 없는 경기라 '첫 홈런'과 겹치지 않는다. 루타 4 + 타점 2 = 6점
        GameBuzzRules.Game six = bat(4, 3, 0, 2, 0, 0, 4);

        // when // then
        assertThat(find(season(10, six), false, false)).get().extracting(GameBuzz::kind).isEqualTo(GameBuzz.SEASON_BEST);
        // 루타 4 + 타점 1 + 볼넷 1(0.5) = 5.5점
        assertThat(find(season(10, bat(4, 3, 0, 1, 1, 0, 4)), false, false)).isEmpty();
        // 앞 경기가 9개면 아직 비교하지 않는다
        assertThat(find(season(9, six), false, false)).isEmpty();

        // given
        // 앞에 같은 점수가 있으면 최고가 아니다
        List<GameBuzzRules.Game> tied = new ArrayList<>(List.of(bat(4, 3, 0, 2, 0, 0, 4)));
        tied.addAll(season(10, six));

        // when // then
        assertThat(find(tied, false, false)).isEmpty();
    }

    @Test
    @DisplayName("첫 홈런도 연속 안타도 시즌 최고도 아닌 날은 화제 문구가 없다")
    void ordinaryDayHasNoBuzz() {
        // given
        // 2안타를 쳤지만 연속도 최고도 처음도 아님
        List<GameBuzzRules.Game> games = new ArrayList<>(List.of(bat(4, 1, 1, 1, 0, 0, 4)));
        games.addAll(season(20, bat(4, 2, 0, 1, 0, 0, 2)));

        // when // then
        assertThat(find(games, false, false)).isEmpty();
    }

    @Test
    @DisplayName("연속 안타 5경기째에 첫 홈런을 치면 첫 홈런을 먼저 알린다")
    void firstBeatsStreak() {
        // given
        // 연속 5경기째이면서 올 시즌 첫 홈런이면 처음이 먼저
        List<GameBuzzRules.Game> games = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            games.add(plain());
        }
        games.add(bat(4, 1, 1, 1, 0, 0, 4));

        // when // then
        assertThat(find(games, false, false)).get().extracting(GameBuzz::kind).isEqualTo(GameBuzz.FIRST);
    }

    @Test
    @DisplayName("통산 기록을 넘으면 제친 선수의 이름과 그 선수의 기록 수를 적는다")
    void passedTextNamesTheSeniorAndCount() {
        // given
        // 이정후 6/24: 한국인 통산 홈런, 이대호(14개) 넘음
        RecordPassed passed = new RecordPassed(1L, 808982, "이정후", null, "homeRuns", "홈런",
                "홈런", java.time.LocalDate.of(2026, 6, 24), "이대호", 14);

        // when // then
        assertThat(GameBuzzFinder.passedText(passed)).isEqualTo("한국인 통산 홈런 이대호(14개) 넘음");

        // given
        RecordPassed games = new RecordPassed(4L, 1, "송성문", null, "games", "경기", "경기",
                java.time.LocalDate.of(2026, 9, 13), "박효준", 68);

        // when // then
        assertThat(GameBuzzFinder.passedText(games)).isEqualTo("한국인 통산 경기 박효준(68경기) 넘음");

        // given
        RecordPassed saves = new RecordPassed(5L, 2, "고우석", null, "saves", "세이브", "세이브",
                java.time.LocalDate.of(2026, 8, 1), "오승환", 42);

        // when // then
        assertThat(GameBuzzFinder.passedText(saves)).isEqualTo("한국인 통산 세이브 오승환(42세이브) 넘음");
    }
}
