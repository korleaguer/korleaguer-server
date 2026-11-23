package com.korleaguer.api.screen;

import com.korleaguer.api.player.MoveEntry;

import com.korleaguer.domain.Level;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 선수 이동 연표의 띠 구간(TimelineSegment)을 이동 기록과 첫 경기로 계산한다(순수 계산, DB를 읽지 않는다).
 * 웹 시안(mock/players/timeline.ts)과 같은 규칙이다.
 * <ul>
 *   <li>상태 = 어디에 있나(메이저·마이너) + 부상자 명단인가 + 팀이 없나(방출·FA 선언 뒤). 표시는 팀 없음 &gt; 부상 &gt; 메이저·마이너 순이다.</li>
 *   <li>이동이 상태를 바꾼다: 콜업(CU·SE)은 메이저, 강등·이관(OPT·OUT)은 마이너, 부상자 명단 등재는 부상, 해제는 부상 끝
 *       (옮겨 간 단계가 있으면 그 단계), 팀 배정(ASG, 재활 경기 배정은 빼고)·계약(SFA)은 옮겨 간 단계, 방출·FA 선언(REL·DFA)은 팀 없음.
 *       트레이드(TR)·지명 할당(DES)·출장 정지(SU) 등은 상태를 바꾸지 않는다.</li>
 *   <li>같은 날 이동은 부상자 명단 해제 → 그 밖 → 방출·FA 선언 순으로 본다(마지막에 '팀 없음'이 이긴다).</li>
 *   <li>시작 상태(from 날짜): 그 기간 첫 경기(메이저·마이너 중 앞선 것)가 첫 상태 이동보다 먼저면 그 경기 단계,
 *       아니면 첫 상태 이동 날의 이동들이 만든 상태를 from까지 거슬러 둔다. 경기도 상태 이동도 없으면 구간이 없다.</li>
 *   <li>구간은 이동 날부터 다음 이동 전날까지이고, 같은 종류가 이어지면 합친다(합친 구간의 팀·단계는 가장 오래 머문 쪽).</li>
 * </ul>
 */
final class PlayerTimelines {

    /** 상태를 바꾸는 이동 종류 */
    private static final Set<String> STATE_TYPES = Set.of("CU", "SE", "OPT", "OUT", "SC", "ASG", "SFA", "REL", "DFA");
    /** 이동 기록의 마이너 팀 이름: "오클라호마시티(트리플A)"(TeamLabels.moveTeam) */
    private static final Pattern MINOR_TEAM = Pattern.compile("^(.*)\\((트리플A|더블A|하이싱글A|싱글A|루키)\\)$");

    private PlayerTimelines() {
    }

    /**
     * 그 기간 첫 경기. 메이저·마이너 각각의 날짜(없으면 null)와 시작 상태의 팀·단계를 채울 값
     *
     * @param majorDay      첫 메이저 경기 날짜
     * @param majorTeamKo   첫 메이저 경기의 팀(한글)
     * @param minorDay      첫 마이너 경기 날짜
     * @param minorLevelKo  첫 마이너 경기의 단계 이름
     */
    record FirstGames(LocalDate majorDay, String majorTeamKo, LocalDate minorDay, String minorLevelKo) {
        static final FirstGames NONE = new FirstGames(null, null, null, null);
    }

    /** 상태: minors면 마이너, inj는 부상자 명단, fa는 팀 없음. teamKo·levelKo는 있는 곳(모르면 null) */
    private record State(boolean minors, boolean inj, boolean fa, String teamKo, String levelKo) {

        TimelineKind kind() {
            return fa ? TimelineKind.NO_TEAM : inj ? TimelineKind.INJURED : minors ? TimelineKind.MINORS : TimelineKind.MLB;
        }

        State injured(boolean value) {
            return new State(minors, value, fa, teamKo, levelKo);
        }

        State free(boolean value) {
            return new State(minors, inj, value, teamKo, levelKo);
        }

        /** 옮겨 간 곳. level이 null이면 단계는 그대로 두고 팀만(있으면) 바꾼다 */
        State placed(Level level, String toTeam) {
            boolean toMinors = level == null ? minors : level == Level.MINORS;
            String team = toTeam;
            String levelName = null;
            if (toTeam != null && toMinors) {
                Matcher m = MINOR_TEAM.matcher(toTeam);
                if (m.matches()) {
                    team = m.group(1);
                    levelName = m.group(2);
                }
            }
            if (toTeam == null && toMinors == minors) {
                team = teamKo;
                levelName = levelKo;
            }
            return new State(toMinors, inj, fa, team, levelName);
        }
    }

    /** 같은 상태가 이어지는 한 토막(시작 날짜). 종류·팀·단계가 바뀔 때마다 새로 연다 */
    private record Piece(LocalDate start, TimelineKind kind, String teamKo, String levelKo) {
    }

    /**
     * 띠 구간.
     *
     * @param moves 이 선수의 이동 기록(선수 화면의 moves와 같은 꼴). 순서·기간은 상관없다(기간 밖은 여기서 거른다)
     * @param from  띠 시작
     * @param to    띠 끝(포함)
     */
    static List<TimelineSegment> segments(List<MoveEntry> moves, LocalDate from, LocalDate to, FirstGames first) {
        if (to.isBefore(from)) {
            return List.of();
        }
        List<MoveEntry> inRange = moves.stream()
                .filter(m -> m.date() != null && !m.date().isBefore(from) && !m.date().isAfter(to))
                .sorted(Comparator.comparing(MoveEntry::date).thenComparingInt(PlayerTimelines::order))
                .toList();
        MoveEntry firstState = inRange.stream()
                .filter(m -> STATE_TYPES.contains(m.typeCode()))
                .findFirst().orElse(null);

        boolean majorFirst = first.majorDay() != null
                && (first.minorDay() == null || !first.majorDay().isAfter(first.minorDay()));
        LocalDate firstGameDay = majorFirst ? first.majorDay() : first.minorDay();
        State state;
        if (firstGameDay != null && (firstState == null || firstGameDay.isBefore(firstState.date()))) {
            state = majorFirst ? new State(false, false, false, first.majorTeamKo(), null)
                    : new State(true, false, false, null, first.minorLevelKo());
        } else if (firstState != null) {
            // 첫 상태 이동 날의 이동들이 만든 상태를 시작 상태로
            state = new State(false, false, false, first.majorTeamKo(), null);
            for (MoveEntry m : inRange) {
                if (m.date().equals(firstState.date())) {
                    state = apply(state, m);
                }
            }
        } else {
            return List.of();
        }

        List<Piece> pieces = new ArrayList<>();
        pieces.add(piece(from, state));
        for (MoveEntry m : inRange) {
            state = apply(state, m);
            Piece next = piece(m.date(), state);
            Piece last = pieces.getLast();
            if (last.kind() == next.kind() && same(last.teamKo(), next.teamKo()) && same(last.levelKo(), next.levelKo())) {
                continue;
            }
            if (last.start().equals(m.date())) {
                pieces.set(pieces.size() - 1, next);
            } else {
                pieces.add(next);
            }
        }
        return merge(pieces, to);
    }

    /** 같은 종류가 이어지는 토막을 하나로. 팀·단계는 가장 오래 머문 토막(같으면 나중 것)의 값 */
    private static List<TimelineSegment> merge(List<Piece> pieces, LocalDate to) {
        List<TimelineSegment> segments = new ArrayList<>();
        int i = 0;
        while (i < pieces.size()) {
            int j = i;
            Piece longest = pieces.get(i);
            long longestDays = 0;
            while (j < pieces.size() && pieces.get(j).kind() == pieces.get(i).kind()) {
                LocalDate end = j + 1 < pieces.size() ? pieces.get(j + 1).start().minusDays(1) : to;
                long days = end.toEpochDay() - pieces.get(j).start().toEpochDay() + 1;
                if (days >= longestDays) {
                    longest = pieces.get(j);
                    longestDays = days;
                }
                j++;
            }
            LocalDate end = j < pieces.size() ? pieces.get(j).start().minusDays(1) : to;
            segments.add(new TimelineSegment(pieces.get(i).start(), end, longest.kind(), longest.levelKo(), longest.teamKo()));
            i = j;
        }
        return segments;
    }

    private static Piece piece(LocalDate start, State state) {
        TimelineKind kind = state.kind();
        return kind == TimelineKind.NO_TEAM ? new Piece(start, kind, null, null)
                : new Piece(start, kind, state.teamKo(), state.levelKo());
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 같은 날 이동을 보는 순서: 부상자 명단 해제 → 그 밖 → 방출·FA 선언 */
    private static int order(MoveEntry m) {
        if ("SC".equals(m.typeCode()) && m.labelKo() != null && m.labelKo().endsWith("해제")) {
            return 0;
        }
        return "REL".equals(m.typeCode()) || "DFA".equals(m.typeCode()) ? 2 : 1;
    }

    private static State apply(State s, MoveEntry m) {
        String code = m.typeCode() == null ? "" : m.typeCode();
        String label = m.labelKo() == null ? "" : m.labelKo();
        Level level = m.toLevel();
        return switch (code) {
            case "CU", "SE" -> s.free(false).placed(Level.MLB, m.toTeam());
            case "OPT", "OUT" -> s.free(false).placed(Level.MINORS, m.toTeam());
            case "SC" -> {
                if (!label.startsWith("부상자 명단")) {
                    yield s;
                }
                if (label.endsWith("해제")) {
                    State released = s.injured(false);
                    yield level == null ? released : released.placed(level, m.toTeam());
                }
                yield s.injured(true).free(false);
            }
            // 재활 경기 배정은 부상을 그대로 두고, 팀 배정은 옮겨 간 단계로
            case "ASG" -> !label.contains("재활") && label.contains("팀 배정") && level != null
                    ? s.placed(level, m.toTeam()) : s;
            case "SFA" -> {
                State signed = s.free(false);
                yield level == null ? signed : signed.placed(level, m.toTeam());
            }
            case "REL", "DFA" -> s.free(true).injured(false);
            default -> s;
        };
    }
}
