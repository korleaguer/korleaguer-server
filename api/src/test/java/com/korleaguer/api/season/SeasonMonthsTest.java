package com.korleaguer.api.season;

import com.korleaguer.api.season.SeasonReport.Month.Kind;
import com.korleaguer.api.season.SeasonReport.Month;
import com.korleaguer.api.season.SeasonReports.GameDay;
import com.korleaguer.api.season.SeasonReports.Input;
import com.korleaguer.api.season.SeasonReports.Move;
import com.korleaguer.api.season.SeasonVerdict.Line;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 시즌 성적표 달별 기록(이슈 #170): 2026 시즌 우리 선수들의 달별 흐름(경기 수·타율·평균자책점·이동)을 본뜬 재료로
 * 달 칸을 확인한다. 숫자는 운영 데이터로 낸 시안(11-16)의 달 칸과 같게 맞췄다(경기별 기록은 합계만 맞춘 값이다).
 */
class SeasonMonthsTest {

    private static final LocalDate OPENING = LocalDate.of(2026, 3, 26);
    private static final LocalDate LAST_DAY = LocalDate.of(2026, 9, 27);

    @Test
    @DisplayName("메이저 풀시즌은 4~9월이 모두 메이저 칸이고 잘 친 6월만 강조된다")
    void fullMajorSeasonShowsEveryMonthAndOnlyTheBigMonthIsHot() {
        // given
        // 이정후: 3월 말 경기는 4월 칸. 짧은 부상자 명단 두 번은 경기가 있어 칸에 안 보인다
        List<GameDay> majors = new ArrayList<>();
        majors.addAll(hitGames(null, OPENING, 29, 103, 31));
        majors.addAll(hitGames(null, LocalDate.of(2026, 5, 1), 21, 85, 23));
        majors.addAll(hitGames(null, LocalDate.of(2026, 6, 1), 26, 99, 38, 8, 12));
        majors.addAll(hitGames(null, LocalDate.of(2026, 7, 1), 23, 87, 21));
        majors.addAll(hitGames(null, LocalDate.of(2026, 8, 1), 26, 97, 24));
        majors.addAll(hitGames(null, LocalDate.of(2026, 9, 1), 17, 72, 12));
        List<Move> moves = List.of(
                move("2026-05-22", "SC", "San Francisco Giants placed RF Jung Hoo Lee on the 10-day injured list."),
                move("2026-05-29", "SC", "San Francisco Giants activated RF Jung Hoo Lee from the 10-day injured list."),
                move("2026-09-21", "SC", "San Francisco Giants placed RF Jung Hoo Lee on the 10-day injured list."));

        // when
        List<Month> months = SeasonMonths.build(hitter(majors, List.of(), moves));

        // then
        // 6월만 wRC+ 130 이상(8홈런·12볼넷)이라 주황
        assertThat(months).extracting(Month::month, Month::kind, Month::games, Month::avg, Month::few, Month::hot)
                .containsExactly(
                        tuple(4, Kind.MLB, 29, 0.301, false, false),
                        tuple(5, Kind.MLB, 21, 0.271, false, false),
                        tuple(6, Kind.MLB, 26, 0.384, false, true),
                        tuple(7, Kind.MLB, 23, 0.241, false, false),
                        tuple(8, Kind.MLB, 26, 0.247, false, false),
                        tuple(9, Kind.MLB, 17, 0.167, false, false));
        assertThat(months.get(2).wrcPlus()).isGreaterThanOrEqualTo(SeasonMonths.HOT_WRC);
        assertThat(months).allSatisfy(m -> {
            assertThat(m.levelKo()).isNull();
            assertThat(m.era()).isNull();
            assertThat(m.eraPlus()).isNull();
        });
    }

    @Test
    @DisplayName("홈런·볼넷 많은 .240의 달이 단타뿐인 .301의 달보다 wRC+가 높다")
    void wrcPlusSeesWalksAndPowerThatAverageMisses() {
        // given
        // 배지환 6월처럼: 타율 .240이어도 볼넷·홈런이 많으면 wRC+는 평균(100) 위. 타율 .301에 단타뿐인 달은 평균 아래
        List<GameDay> majors = new ArrayList<>();
        majors.addAll(hitGames(null, LocalDate.of(2026, 4, 1), 29, 103, 31));
        majors.addAll(hitGames(null, LocalDate.of(2026, 6, 1), 26, 96, 23, 8, 20));

        // when
        List<Month> months = SeasonMonths.build(hitter(majors, List.of(), List.of()));

        // then
        assertThat(months.get(0)).extracting(Month::avg, Month::wrcPlus, Month::hot).containsExactly(0.301, 69, false);
        assertThat(months.get(2)).extracting(Month::avg, Month::wrcPlus, Month::hot).containsExactly(0.24, 144, true);
    }

    @Test
    @DisplayName("마이너에서 뛴 달의 wRC+는 그 단계 리그 평균과 견준다")
    void minorMonthsAreMeasuredAgainstTheirOwnLeague() {
        // given
        // 같은 트리플A 기록도 타고 리그(2026 트리플A wOBA .338)와 비교하면 MLB 표준값과 비교할 때보다 낮다
        List<GameDay> minors = hitGames("AAA", LocalDate.of(2026, 7, 1), 20, 72, 21, 2, 8);
        Input input = hitter(List.of(), minors, List.of());

        // when
        int standard = SeasonMonths.build(input).get(3).wrcPlus();
        int tripleA = SeasonMonths.build(input, Map.of("AAA", new LeagueEnv(0.338, 0.138, 4.99)))
                .get(3).wrcPlus();

        // then
        assertThat(tripleA).isLessThan(standard);
        assertThat(tripleA).isBetween(90, 110);
    }

    @Test
    @DisplayName("메이저에서 한 달만 뛰면 그 달 wRC+가 공식 시즌 wRC+와 같다")
    void majorMonthsFollowTheOfficialSeasonWrcPlus() {
        // given
        // 공식 시즌 wRC+(구장 보정 포함)가 120이면, 한 달만 뛴 선수의 그 달 wRC+도 120이다
        List<GameDay> majors = hitGames(null, LocalDate.of(2026, 5, 1), 25, 90, 25, 3, 10);
        Input input = new Input(1, Role.HITTER, 2026, OPENING, LAST_DAY, false, new Line(2026, 100, 0, 120.0, 1.0),
                List.of(), majors, List.of(), List.of());

        // when // then
        assertThat(SeasonMonths.build(input).get(1).wrcPlus()).isEqualTo(120);
    }

    @Test
    @DisplayName("재활 경기는 달 기록에서 빠지고 부상으로 거의 못 뛴 달은 부상 칸이 된다")
    void rehabGamesAreLeftOutAndRehabMonthsAreInjured() {
        // given
        // 김하성: 개막일 부상자 명단 → 4월 말 재활 → 5월 11일 복귀, 7월 4일 다시 부상 → 재활 13경기 → 8월 3일 복귀
        List<GameDay> majors = new ArrayList<>();
        majors.addAll(hitGames(null, LocalDate.of(2026, 5, 11), 12, 42, 4));
        majors.addAll(hitGames(null, LocalDate.of(2026, 6, 1), 14, 31, 1));
        majors.addAll(hitGames(null, LocalDate.of(2026, 7, 1), 1, 3, 0));
        majors.addAll(hitGames(null, LocalDate.of(2026, 8, 3), 8, 26, 4));
        majors.addAll(hitGames(null, LocalDate.of(2026, 9, 1), 16, 43, 8));
        List<GameDay> minors = new ArrayList<>();
        minors.addAll(hitGames("A+", LocalDate.of(2026, 4, 29), 1, 3, 1));
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 5, 1), 8, 28, 7));
        minors.addAll(hitGames("ROK", LocalDate.of(2026, 7, 13), 3, 8, 2));
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 7, 17), 10, 35, 9));
        List<Move> moves = List.of(
                move("2026-03-25", "SC", "Atlanta Braves placed SS Ha-Seong Kim on the 10-day injured list. "
                        + "Right middle finger sprain."),
                move("2026-04-28", "ASG", "Atlanta Braves sent SS Ha-Seong Kim on a rehab assignment to "
                        + "Columbus Clingstones."),
                move("2026-05-05", "ASG", "Atlanta Braves sent SS Ha-Seong Kim on a rehab assignment to "
                        + "Gwinnett Stripers."),
                move("2026-05-11", "SC", "Atlanta Braves activated SS Ha-Seong Kim from the 10-day injured list."),
                move("2026-07-04", "SC", "Atlanta Braves placed SS Ha-Seong Kim on the 10-day injured list. "
                        + "Right finger inflammation."),
                move("2026-07-13", "ASG", "Atlanta Braves sent SS Ha-Seong Kim on a rehab assignment to FCL Braves."),
                move("2026-07-17", "ASG", "Atlanta Braves sent SS Ha-Seong Kim on a rehab assignment to "
                        + "Gwinnett Stripers."),
                move("2026-08-03", "SC", "Atlanta Braves activated SS Ha-Seong Kim from the 10-day injured list."));

        // when
        List<Month> months = SeasonMonths.build(hitter(majors, minors, moves));

        // then
        // 5월: 재활 8경기(트리플A)는 빼고 메이저 12경기만. 8월 8경기는 경기 적은 달
        assertThat(months).extracting(Month::month, Month::kind, Month::games, Month::avg, Month::few)
                .containsExactly(
                        tuple(4, Kind.INJURED, 0, null, false),
                        tuple(5, Kind.MLB, 12, 0.095, false),
                        tuple(6, Kind.MLB, 14, 0.032, false),
                        tuple(7, Kind.INJURED, 0, null, false),
                        tuple(8, Kind.MLB, 8, 0.154, true),
                        tuple(9, Kind.MLB, 16, 0.186, false));
    }

    @Test
    @DisplayName("투수의 달은 가장 많이 던진 단계로 적고 FA 선언 뒤 달은 FA 칸이 된다")
    void pitcherClimbsLevelsThenEndsAsFreeAgent() {
        // given
        // 고우석: 더블A → 트리플A → 7월 트레이드 뒤 메이저 4등판 → 트리플A → 8월 30일 아웃라이트·FA 선언
        List<GameDay> majors = pitchGames(null, LocalDate.of(2026, 7, 8), 4, 12, 4);
        List<GameDay> minors = new ArrayList<>();
        minors.addAll(pitchGames("AAA", LocalDate.of(2026, 3, 28), 2, 6, 1));
        minors.addAll(pitchGames("AA", LocalDate.of(2026, 4, 9), 6, 40, 0));
        minors.addAll(pitchGames("AAA", LocalDate.of(2026, 5, 9), 7, 26, 2));
        minors.addAll(pitchGames("AAA", LocalDate.of(2026, 6, 1), 9, 37, 2));
        minors.addAll(pitchGames("AAA", LocalDate.of(2026, 7, 1), 2, 6, 1));
        minors.addAll(pitchGames("AAA", LocalDate.of(2026, 8, 1), 8, 28, 13));
        List<Move> moves = List.of(
                move("2026-02-21", "ASG", "Detroit Tigers assigned RHP Woo-Suk Go to Toledo Mud Hens."),
                move("2026-04-08", "ASG", "RHP Woo-Suk Go assigned to Erie SeaWolves from Toledo Mud Hens."),
                move("2026-05-08", "ASG", "RHP Woo-Suk Go assigned to Toledo Mud Hens from Erie SeaWolves."),
                move("2026-07-05", "TR", "Detroit Tigers traded RHP Woo-Suk Go to Minnesota Twins."),
                move("2026-07-06", "ASG", "Minnesota Twins assigned RHP Woo-Suk Go to St. Paul Saints."),
                move("2026-07-07", "SE", "Minnesota Twins selected the contract of RHP Woo-Suk Go from St. Paul Saints."),
                move("2026-07-21", "OPT", "Minnesota Twins optioned RHP Woo-Suk Go to St. Paul Saints."),
                move("2026-07-25", "SU", "St. Paul Saints suspended RHP Woo-Suk Go."),
                move("2026-08-28", "DES", "Minnesota Twins designated RHP Woo-Suk Go for assignment."),
                // 같은 날 아웃라이트 뒤 FA 선언: FA 선언을 나중에 본다
                new Move(LocalDate.of(2026, 8, 30), "DFA", "Declared Free Agency", "RHP Woo-Suk Go elected free agency."),
                move("2026-08-30", "OUT", "Minnesota Twins sent RHP Woo-Suk Go outright to St. Paul Saints."));

        // 2026 리그 평균자책점: MLB 4.17, 트리플A 4.99, 더블A 4.85
        Map<String, LeagueEnv> leagues = Map.of("MLB", new LeagueEnv(0.314, 0.118, 4.17),
                "AAA", new LeagueEnv(0.338, 0.138, 4.99), "AA", new LeagueEnv(0.331, 0.137, 4.85));

        // when
        List<Month> months = SeasonMonths.build(new Input(808970, Role.PITCHER, 2026, OPENING, LAST_DAY, false, null,
                List.of(), majors, minors, moves), leagues);

        // then
        // ERA+: 무자책 달은 300(최대), 1.46은 4.99/1.46 = 342라 300에서 자른다
        assertThat(months).extracting(Month::month, Month::kind, Month::levelKo, Month::games, Month::era,
                        Month::eraPlus, Month::few)
                .containsExactly(
                        tuple(4, Kind.MINORS, "더블A", 6, 0.0, 300, false),
                        tuple(5, Kind.MINORS, "트리플A", 7, 2.08, 240, false),
                        tuple(6, Kind.MINORS, "트리플A", 9, 1.46, 300, false),
                        tuple(7, Kind.MLB, null, 4, 9.0, 46, true),
                        tuple(8, Kind.MINORS, "트리플A", 8, 12.54, 40, false),
                        tuple(9, Kind.FREE_AGENT, null, 0, null, null, false));
        assertThat(months).allSatisfy(m -> {
            assertThat(m.avg()).isNull();
            assertThat(m.wrcPlus()).isNull();
            assertThat(m.hot()).isFalse();
        });
    }

    @Test
    @DisplayName("메이저와 마이너 경기 수가 같은 달은 메이저, 마이너 부상 뒤 달은 부상 칸이다")
    void tiedMonthGoesToMajorsAndMinorInjuryEndsTheSeason() {
        // given
        // 배지환: 트리플A → 8월 방출·밀워키 계약·콜업(4경기) → 지명 할당·아웃라이트 → 8월 25일 마이너 부상자 명단
        List<GameDay> minors = new ArrayList<>();
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 4, 1), 21, 86, 27));
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 5, 1), 27, 93, 26));
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 6, 1), 23, 80, 18));
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 7, 1), 17, 58, 11));
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 8, 1), 3, 12, 3));
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 8, 21), 1, 4, 1));
        List<GameDay> majors = hitGames(null, LocalDate.of(2026, 8, 10), 4, 8, 4);
        List<Move> moves = List.of(
                move("2026-01-08", "OUT", "New York Mets sent CF Ji Hwan Bae outright to Syracuse Mets."),
                move("2026-08-04", "REL", "Syracuse Mets released CF Ji Hwan Bae."),
                move("2026-08-09", "SFA", "Milwaukee Brewers signed free agent CF Ji Hwan Bae."),
                move("2026-08-09", "SE", "Milwaukee Brewers selected the contract of CF Ji Hwan Bae."),
                move("2026-08-15", "DES", "Milwaukee Brewers designated CF Ji Hwan Bae for assignment."),
                move("2026-08-19", "OUT", "Milwaukee Brewers sent CF Ji Hwan Bae outright to Nashville Sounds."),
                move("2026-08-25", "SC", "Nashville Sounds placed CF Ji Hwan Bae on the 7-day injured list. "
                        + "Left knee sprain."),
                move("2026-09-01", "SC", "Nashville Sounds transferred CF Ji Hwan Bae from the 7-day injured list "
                        + "to the full-season injured list."));

        // when
        List<Month> months = SeasonMonths.build(hitter(majors, minors, moves));

        // then
        // 8월: 메이저 4경기 = 트리플A 4경기 → 메이저 칸(4경기 .500은 경기 적은 달)
        assertThat(months).extracting(Month::month, Month::kind, Month::levelKo, Month::games, Month::avg, Month::few)
                .containsExactly(
                        tuple(4, Kind.MINORS, "트리플A", 21, 0.314, false),
                        tuple(5, Kind.MINORS, "트리플A", 27, 0.280, false),
                        tuple(6, Kind.MINORS, "트리플A", 23, 0.225, false),
                        tuple(7, Kind.MINORS, "트리플A", 17, 0.190, false),
                        tuple(8, Kind.MLB, null, 4, 0.5, true),
                        tuple(9, Kind.INJURED, null, 0, null, false));
    }

    @Test
    @DisplayName("마이너 부상자 명단 해제 기록이 없어도 그 뒤 경기를 보통 경기로 센다")
    void minorInjuredListWithoutActivationStillCountsGames() {
        // given
        // 마이너 부상자 명단은 해제 기록이 없을 때가 많다. 재활 배정이 없으면 그 뒤 경기도 보통 경기다
        List<GameDay> minors = hitGames("AAA", LocalDate.of(2026, 5, 10), 18, 70, 20);
        List<Move> moves = List.of(
                move("2026-05-01", "SC", "Oklahoma City Comets placed 2B Hyeseong Kim on the 7-day injured list."));

        // when
        List<Month> months = SeasonMonths.build(hitter(List.of(), minors, moves));

        // then
        assertThat(months.get(1)).extracting(Month::month, Month::kind, Month::levelKo, Month::games)
                .containsExactly(5, Kind.MINORS, "트리플A", 18);
        assertThat(months.get(0).kind()).isEqualTo(Kind.NONE);
    }

    @Test
    @DisplayName("재활 배정된 달은 부상 칸이고 재활 배정 없는 새 부상 뒤 경기는 보통 경기다")
    void rehabFollowsTheCurrentInjuryStint() {
        // given
        // 같은 날 등재 + 재활 배정이면 재활이다. 60일 명단으로 옮겨도 재활은 이어진다.
        // 해제 기록 없이 끝난 부상 뒤 새 등재(재활 배정 없음)의 마이너 경기는 보통 경기다
        List<GameDay> minors = new ArrayList<>();
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 4, 10), 12, 40, 10));
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 6, 10), 15, 50, 15));
        List<Move> moves = List.of(
                move("2026-04-05", "SC", "Seattle Mariners placed 2B A on the 10-day injured list."),
                move("2026-04-05", "ASG", "Seattle Mariners sent 2B A on a rehab assignment to Tacoma Rainiers."),
                move("2026-04-15", "SC", "Seattle Mariners transferred 2B A from the 10-day injured list to the "
                        + "60-day injured list."),
                move("2026-06-01", "SC", "Tacoma Rainiers placed 2B A on the 7-day injured list."));

        // when
        List<Month> months = SeasonMonths.build(hitter(List.of(), minors, moves));

        // then
        assertThat(months.get(0).kind()).isEqualTo(Kind.INJURED);
        assertThat(months.get(2)).extracting(Month::kind, Month::games).containsExactly(Kind.MINORS, 15);
    }

    @Test
    @DisplayName("며칠뿐인 3월과 10월 경기는 4월과 9월 칸에 합친다")
    void shortFirstAndLastMonthsJoinTheirNeighbours() {
        // given
        // 3월 26일 개막(3월 6일치)은 4월 칸, 10월 2일 끝(10월 2일치)은 9월 칸
        LocalDate lastDay = LocalDate.of(2026, 10, 2);
        List<GameDay> majors = List.of(hitGames(null, LocalDate.of(2026, 3, 27), 1, 4, 2).getFirst(),
                hitGames(null, LocalDate.of(2026, 10, 1), 1, 4, 1).getFirst());

        // when
        List<Month> months = SeasonMonths.build(new Input(1, Role.HITTER, 2026, OPENING, lastDay, false, null,
                List.of(), majors, List.of(), List.of()));

        // then
        assertThat(months).extracting(Month::month).containsExactly(4, 5, 6, 7, 8, 9);
        assertThat(months.getFirst()).extracting(Month::games, Month::avg).containsExactly(1, 0.5);
        assertThat(months.getLast()).extracting(Month::games, Month::avg).containsExactly(1, 0.25);
        assertThat(months.subList(1, 5)).extracting(Month::kind).containsOnly(Kind.NONE);
    }

    @Test
    @DisplayName("아웃 수를 모르는 등판은 투수의 달 평균자책점과 등판 수에서 빠진다")
    void pitcherGameWithUnknownOutsIsLeftOutOfTheMonth() {
        // given
        // 4월에 9아웃 1자책 세 번(평균자책점 3.00), 아웃 수를 못 받은 등판 한 번(5자책)
        List<GameDay> majors = new ArrayList<>(pitchGames(null, LocalDate.of(2026, 4, 1), 3, 27, 3));
        Pitching outsUnknown = new Pitching(null, 0, 5, 5, 0, 0, 0, 20, 0, 0, 0);
        majors.add(new GameDay(LocalDate.of(2026, 4, 20), null, null, null, null, outsUnknown, false, null));

        // when
        List<Month> months = SeasonMonths.build(new Input(1, Role.PITCHER, 2026, OPENING, LAST_DAY, false, null,
                List.of(), majors, List.of(), List.of()), Map.of());

        // then
        assertThat(months.getFirst()).extracting(Month::month, Month::kind, Month::games, Month::era)
                .containsExactly(4, Kind.MLB, 3, 3.0);
    }


    // ---- 재료 ----

    private static Input hitter(List<GameDay> majors, List<GameDay> minors, List<Move> moves) {
        return new Input(1, Role.HITTER, 2026, OPENING, LAST_DAY, false, null, List.of(), majors, minors, moves);
    }

    private static Move move(String date, String typeCode, String description) {
        return new Move(LocalDate.parse(date), typeCode, null, description);
    }

    /** from부터 하루 한 경기씩 count경기. 타수·안타 합계가 atBats·hits가 되게 나눈다. level이 null이면 메이저 */
    private static List<GameDay> hitGames(String level, LocalDate from, int count, int atBats, int hits) {
        return hitGames(level, from, count, atBats, hits, 0, 0);
    }

    /** 안타 중 homeRuns개는 홈런, 볼넷 walks개 */
    private static List<GameDay> hitGames(String level, LocalDate from, int count, int atBats, int hits, int homeRuns,
                                          int walks) {
        List<GameDay> games = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int ab = share(atBats, count, i);
            int h = share(hits, count, i);
            int hr = Math.min(h, share(homeRuns, count, i));
            int bb = share(walks, count, i);
            Batting batting = new Batting(ab, 0, h, 0, 0, hr, 0, bb, 0, 0, 0, 0, ab + bb, null);
            games.add(new GameDay(from.plusDays(i), level, null, null, batting, null, false, null));
        }
        return games;
    }

    /** from부터 사흘에 한 번 count등판. 아웃·자책 합계가 outs·earnedRuns가 되게 나눈다 */
    private static List<GameDay> pitchGames(String level, LocalDate from, int count, int outs, int earnedRuns) {
        List<GameDay> games = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Pitching pitching = new Pitching(share(outs, count, i), 0, share(earnedRuns, count, i),
                    share(earnedRuns, count, i), 0, 1, 0, 20, 0, 0, 0);
            games.add(new GameDay(from.plusDays(i * 3L), level, null, null, null, pitching, false, null));
        }
        return games;
    }

    /** total을 n개로 나눈 i번째 몫(앞쪽이 하나씩 더 많다) */
    private static int share(int total, int n, int i) {
        return total / n + (i < total % n ? 1 : 0);
    }
}
