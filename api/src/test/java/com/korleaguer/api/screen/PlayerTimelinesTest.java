package com.korleaguer.api.screen;

import com.korleaguer.api.player.MoveEntry;

import com.korleaguer.domain.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.korleaguer.api.screen.TimelineKind.INJURED;
import static com.korleaguer.api.screen.TimelineKind.MINORS;
import static com.korleaguer.api.screen.TimelineKind.MLB;
import static com.korleaguer.api.screen.TimelineKind.NO_TEAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 선수 이동 연표의 띠 구간(이슈 #203). 2026 시즌 우리 선수 여섯 명의 운영 이동 기록(선수 화면 moves, 2026-11-19)을
 * 그대로 옮긴 재료로, 띠가 사용자가 확인한 흐름과 같게 나오는지 본다. 첫 경기 날짜는 그 선수의 첫 메이저·마이너 경기다.
 */
class PlayerTimelinesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 19);

    @Test
    @DisplayName("이정후는 메이저 시즌 중에 두 번 다쳐 부상 구간이 둘이다")
    void leeJungHooIsInjuredTwiceAroundAMajorSeason() {
        // given
        List<MoveEntry> moves = List.of(
                move("2026-05-22", "SC", "부상자 명단 등재", "샌프란시스코", Level.MLB),
                move("2026-05-29", "SC", "부상자 명단 해제", "샌프란시스코", Level.MLB),
                move("2026-09-21", "SC", "부상자 명단 등재", "샌프란시스코", Level.MLB),
                move("2026-09-29", "SC", "부상자 명단 해제", "샌프란시스코", Level.MLB));

        // when
        List<TimelineSegment> segments = PlayerTimelines.segments(moves, FROM, TO,
                firstGames("2026-03-26", "샌프란시스코", null, null));

        // then
        assertThat(spans(segments)).containsExactly(
                tuple(MLB, "2026-03-01", "2026-05-21"),
                tuple(INJURED, "2026-05-22", "2026-05-28"),
                tuple(MLB, "2026-05-29", "2026-09-20"),
                tuple(INJURED, "2026-09-21", "2026-09-28"),
                tuple(MLB, "2026-09-29", "2026-11-19"));
        assertThat(segments.getFirst().teamKo()).isEqualTo("샌프란시스코");
    }

    @Test
    @DisplayName("김혜성은 두 번 내려갔다 올라와 마이너와 메이저 구간이 번갈아 나온다")
    void kimHyeSeongGoesDownAndUpTwice() {
        // given
        List<MoveEntry> moves = List.of(
                move("2026-03-22", "OPT", "마이너 강등(옵션)", "오클라호마시티(트리플A)", Level.MINORS),
                move("2026-04-05", "CU", "메이저 콜업", "LA 다저스", Level.MLB),
                move("2026-05-29", "OPT", "마이너 강등(옵션)", "오클라호마시티(트리플A)", Level.MINORS),
                move("2026-09-28", "CU", "메이저 콜업", "LA 다저스", Level.MLB));

        // when
        List<TimelineSegment> segments = PlayerTimelines.segments(moves, FROM, TO,
                firstGames("2026-04-05", "LA 다저스", "2026-03-27", "트리플A"));

        // then
        assertThat(spans(segments)).containsExactly(
                tuple(MINORS, "2026-03-01", "2026-04-04"),
                tuple(MLB, "2026-04-05", "2026-05-28"),
                tuple(MINORS, "2026-05-29", "2026-09-27"),
                tuple(MLB, "2026-09-28", "2026-11-19"));
        // 마이너 구간은 이동 기록의 팀 이름에서 지명과 단계를 나눈다
        assertThat(segments.getFirst()).extracting(TimelineSegment::teamKo, TimelineSegment::levelKo)
                .containsExactly("오클라호마시티", "트리플A");
        assertThat(segments.get(1)).extracting(TimelineSegment::teamKo, TimelineSegment::levelKo).containsExactly("LA 다저스", null);
    }

    @Test
    @DisplayName("김하성은 재활 경기를 뛰어도 해제 전날까지 부상 구간이다")
    void kimHaSeongStartsInjuredAndRehabAssignmentsKeepTheInjury() {
        // given
        List<MoveEntry> moves = List.of(
                move("2025-12-15", "SFA", "계약", "애틀랜타", Level.MLB), // 기간 밖
                move("2026-03-25", "SC", "부상자 명단 등재", "애틀랜타", Level.MLB),
                move("2026-04-28", "ASG", "재활 경기 배정", "콜럼버스(더블A)", Level.MINORS),
                move("2026-05-05", "ASG", "재활 경기 배정", "귀넷(트리플A)", Level.MINORS),
                move("2026-05-11", "SC", "부상자 명단 해제", "애틀랜타", Level.MLB),
                move("2026-07-04", "SC", "부상자 명단 등재", "애틀랜타", Level.MLB),
                move("2026-07-13", "ASG", "재활 경기 배정", "FCL 브레이브스(루키)", Level.MINORS),
                move("2026-07-17", "ASG", "재활 경기 배정", "귀넷(트리플A)", Level.MINORS),
                move("2026-08-03", "SC", "부상자 명단 해제", "애틀랜타", Level.MLB),
                move("2026-10-29", "DFA", "FA 선언", "애틀랜타", Level.MLB));

        // when
        List<TimelineSegment> segments = PlayerTimelines.segments(moves, FROM, TO,
                firstGames("2026-03-26", "애틀랜타", "2026-04-29", "더블A"));

        // then
        // 재활 경기(마이너)를 뛰어도 부상 구간이고, 구간 끝은 해제일 전날이다
        assertThat(spans(segments)).containsExactly(
                tuple(INJURED, "2026-03-01", "2026-05-10"),
                tuple(MLB, "2026-05-11", "2026-07-03"),
                tuple(INJURED, "2026-07-04", "2026-08-02"),
                tuple(MLB, "2026-08-03", "2026-10-28"),
                tuple(NO_TEAM, "2026-10-29", "2026-11-19"));
        assertThat(segments.getLast()).extracting(TimelineSegment::teamKo, TimelineSegment::levelKo).containsExactly(null, null);
    }

    @Test
    @DisplayName("송성문은 부상자 명단에서 해제된 날 바로 마이너 구간으로 넘어간다")
    void songSeongMunLeavesTheInjuredListStraightIntoTheMinors() {
        // given
        List<MoveEntry> moves = List.of(
                move("2025-12-22", "SFA", "계약", "샌디에이고", Level.MLB),
                move("2026-03-25", "SC", "부상자 명단 등재", "샌디에이고", Level.MLB),
                move("2026-03-27", "ASG", "재활 경기 배정", "엘패소(트리플A)", Level.MINORS),
                // 같은 날 강등이 해제보다 먼저 적혀 있어도 해제를 먼저 본다
                move("2026-04-16", "OPT", "마이너 강등(옵션)", "엘패소(트리플A)", Level.MINORS),
                move("2026-04-16", "SC", "부상자 명단 해제", "샌디에이고", Level.MLB),
                move("2026-04-25", "CU", "메이저 콜업", "샌디에이고", Level.MLB),
                move("2026-04-27", "OPT", "마이너 강등(옵션)", "엘패소(트리플A)", Level.MINORS),
                move("2026-05-05", "CU", "메이저 콜업", "샌디에이고", Level.MLB),
                move("2026-08-24", "OPT", "마이너 강등(옵션)", "엘패소(트리플A)", Level.MINORS),
                move("2026-08-30", "CU", "메이저 콜업", "샌디에이고", Level.MLB));

        // when
        List<TimelineSegment> segments = PlayerTimelines.segments(moves, FROM, TO,
                firstGames("2026-04-25", "샌디에이고", "2026-03-27", "트리플A"));

        // then
        assertThat(spans(segments)).containsExactly(
                tuple(INJURED, "2026-03-01", "2026-04-15"),
                tuple(MINORS, "2026-04-16", "2026-04-24"),
                tuple(MLB, "2026-04-25", "2026-04-26"),
                tuple(MINORS, "2026-04-27", "2026-05-04"),
                tuple(MLB, "2026-05-05", "2026-08-23"),
                tuple(MINORS, "2026-08-24", "2026-08-29"),
                tuple(MLB, "2026-08-30", "2026-11-19"));
    }

    @Test
    @DisplayName("고우석은 마이너 팀을 옮겨도 한 구간이고 FA 선언 뒤 팀이 없다")
    void goWooSeokMergesMinorStopsAndEndsWithoutATeam() {
        // given
        List<MoveEntry> moves = List.of(
                move("2026-04-08", "ASG", "팀 배정", "이리(더블A)", Level.MINORS),
                move("2026-05-08", "ASG", "팀 배정", "톨레도(트리플A)", Level.MINORS),
                move("2026-07-05", "TR", "트레이드", "미네소타", Level.MLB), // 상태는 그대로
                move("2026-07-06", "ASG", "팀 배정", "세인트폴(트리플A)", Level.MINORS),
                move("2026-07-07", "SE", "메이저 콜업", "미네소타", Level.MLB),
                move("2026-07-21", "OPT", "마이너 강등(옵션)", "세인트폴(트리플A)", Level.MINORS),
                move("2026-07-25", "SU", "출장 정지", "세인트폴(트리플A)", Level.MINORS),
                move("2026-08-28", "DES", "지명 할당(DFA)", "미네소타", Level.MLB),
                move("2026-08-30", "DFA", "FA 선언", "세인트폴(트리플A)", Level.MINORS),
                move("2026-08-30", "OUT", "마이너 이관(아웃라이트)", "세인트폴(트리플A)", Level.MINORS));

        // when
        List<TimelineSegment> segments = PlayerTimelines.segments(moves, FROM, TO,
                firstGames("2026-07-07", "미네소타", "2026-03-29", "트리플A"));

        // then
        // 마이너 안에서 팀·단계가 바뀌어도 한 구간이다. 같은 날 방출·FA 선언이 이관보다 나중이라 팀 없음이 이긴다
        assertThat(spans(segments)).containsExactly(
                tuple(MINORS, "2026-03-01", "2026-07-06"),
                tuple(MLB, "2026-07-07", "2026-07-20"),
                tuple(MINORS, "2026-07-21", "2026-08-29"),
                tuple(NO_TEAM, "2026-08-30", "2026-11-19"));
        // 합친 구간의 팀·단계는 가장 오래 머문 곳(5/8부터 톨레도)
        assertThat(segments.getFirst()).extracting(TimelineSegment::teamKo, TimelineSegment::levelKo)
                .containsExactly("톨레도", "트리플A");
    }

    @Test
    @DisplayName("배지환은 방출 뒤 메이저에 갔다가 마이너로 내려가 다치고 FA가 된다")
    void baeJiHwanReturnsAfterBeingReleasedAndEndsInjuredThenFreeAgent() {
        // given
        List<MoveEntry> moves = List.of(
                move("2026-01-08", "OUT", "마이너 이관(아웃라이트)", "시러큐스(트리플A)", Level.MINORS), // 기간 밖
                move("2026-08-04", "REL", "방출", "시러큐스(트리플A)", Level.MINORS),
                move("2026-08-09", "SFA", "계약", "밀워키", Level.MLB),
                move("2026-08-09", "SE", "메이저 콜업", "밀워키", Level.MLB),
                move("2026-08-15", "DES", "지명 할당(DFA)", "밀워키", Level.MLB),
                move("2026-08-19", "OUT", "마이너 이관(아웃라이트)", "내슈빌(트리플A)", Level.MINORS),
                move("2026-08-25", "SC", "부상자 명단 등재", "내슈빌(트리플A)", Level.MINORS),
                move("2026-09-01", "SC", "부상자 명단 이동", "내슈빌(트리플A)", Level.MINORS),
                move("2026-11-02", "DFA", "FA 선언", "내슈빌(트리플A)", Level.MINORS));

        // when
        List<TimelineSegment> segments = PlayerTimelines.segments(moves, FROM, TO,
                firstGames("2026-08-09", "밀워키", "2026-03-28", "트리플A"));

        // then
        assertThat(spans(segments)).containsExactly(
                tuple(MINORS, "2026-03-01", "2026-08-03"),
                tuple(NO_TEAM, "2026-08-04", "2026-08-08"),
                tuple(MLB, "2026-08-09", "2026-08-18"),
                tuple(MINORS, "2026-08-19", "2026-08-24"),
                tuple(INJURED, "2026-08-25", "2026-11-01"),
                tuple(NO_TEAM, "2026-11-02", "2026-11-19"));
        // 마이너에서 다친 구간은 있던 팀·단계를 그대로 보인다
        assertThat(segments.get(4)).extracting(TimelineSegment::teamKo, TimelineSegment::levelKo)
                .containsExactly("내슈빌", "트리플A");
    }

    @Test
    @DisplayName("띠 구간은 시작부터 끝까지 빈 날 없이 이어진다")
    void segmentsCoverTheWholeBandWithoutGaps() {
        // given
        List<MoveEntry> moves = List.of(
                move("2026-05-22", "SC", "부상자 명단 등재", "샌프란시스코", Level.MLB),
                move("2026-05-29", "SC", "부상자 명단 해제", "샌프란시스코", Level.MLB));

        // when
        List<TimelineSegment> segments = PlayerTimelines.segments(moves, FROM, TO,
                firstGames("2026-03-26", "샌프란시스코", null, null));

        // then
        assertThat(segments.getFirst().from()).isEqualTo(FROM);
        assertThat(segments.getLast().to()).isEqualTo(TO);
        for (int i = 1; i < segments.size(); i++) {
            assertThat(segments.get(i).from()).isEqualTo(segments.get(i - 1).to().plusDays(1));
            assertThat(segments.get(i).kind()).isNotEqualTo(segments.get(i - 1).kind());
        }
    }

    @Test
    @DisplayName("경기도 상태를 바꾸는 이동도 없거나 끝 날이 시작보다 앞서면 구간이 없다")
    void noRecordsOrAnEmptyBandGiveNoSegments() {
        // when // then
        // 그 시즌 경기도 상태를 바꾸는 이동도 없다(없는 시즌)
        assertThat(PlayerTimelines.segments(List.of(), FROM, TO, PlayerTimelines.FirstGames.NONE)).isEmpty();
        assertThat(PlayerTimelines.segments(List.of(move("2026-08-05", "TR", "트레이드", "미네소타", Level.MLB)), FROM, TO,
                PlayerTimelines.FirstGames.NONE)).isEmpty();
        // 시즌이 아직 오지 않아 띠 끝이 시작보다 앞선다
        assertThat(PlayerTimelines.segments(List.of(), TO, FROM, firstGames("2026-03-26", "샌프란시스코", null, null)))
                .isEmpty();
    }

    @Test
    @DisplayName("첫 경기가 첫 이동보다 먼저면 그 경기로, 아니면 이동으로 시작 상태를 정한다")
    void aGameBeforeTheFirstMoveSetsTheStartingStateOtherwiseTheMoveDoes() {
        // given
        // 마이너 경기가 첫 이동(콜업)보다 먼저면 마이너에서 시작, 첫 이동이 먼저면 그 이동 직전 상태를 거슬러 둔다
        List<MoveEntry> upMove = List.of(move("2026-06-10", "CU", "메이저 콜업", "시애틀", Level.MLB));

        // when // then
        assertThat(spans(PlayerTimelines.segments(upMove, FROM, TO, firstGames(null, null, "2026-04-01", "더블A"))))
                .containsExactly(tuple(MINORS, "2026-03-01", "2026-06-09"), tuple(MLB, "2026-06-10", "2026-11-19"));
        assertThat(spans(PlayerTimelines.segments(upMove, FROM, TO, firstGames(null, null, "2026-07-01", "더블A"))))
                .containsExactly(tuple(MLB, "2026-03-01", "2026-11-19"));
    }

    private static MoveEntry move(String date, String typeCode, String label, String toTeam, Level toLevel) {
        return new MoveEntry(LocalDate.parse(date), typeCode, label, null, toTeam, toLevel);
    }

    private static PlayerTimelines.FirstGames firstGames(String majorDay, String majorTeam, String minorDay,
                                                         String minorLevel) {
        return new PlayerTimelines.FirstGames(majorDay == null ? null : LocalDate.parse(majorDay), majorTeam,
                minorDay == null ? null : LocalDate.parse(minorDay), minorLevel);
    }

    private static List<org.assertj.core.groups.Tuple> spans(List<TimelineSegment> segments) {
        return segments.stream().map(s -> tuple(s.kind(), s.from().toString(), s.to().toString())).toList();
    }
}
