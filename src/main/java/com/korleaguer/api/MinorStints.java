package com.korleaguer.api;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.PlayerMove;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 시즌 중 마이너로 내려가 있던 기간(미국 날짜). 이 기간의 메이저 팀 경기는 결장 줄을 만들지 않는다
 * (트리플A에 있던 날이 '결장'으로 찍히면 벤치에 앉아 있던 것처럼 읽힌다, 이슈 #134).
 * <ul>
 *   <li>시작: 강등(옵션 OPT·아웃라이트 OUT·재활이 아닌 마이너 배정 ASG). 그날 경기부터 뺀다.</li>
 *   <li>끝: 콜업(CU)·계약 선택(SE). 그날 경기부터 다시 결장일 수 있다(명단에 올라와 있다).
 *       팀이 바뀌는 기록(트레이드·클레임·계약·방출·FA 선언)도 끝으로 본다. 마이너에 있다가 다른 팀이 클레임해 바로 메이저
 *       명단에 올리면 콜업 기록이 없다. 결장 줄은 팀마다 그 팀 첫 출전부터만 붙으니, 닫아도 새 팀 마이너 기간에 줄이 생기지
 *       않는다(새 팀에서 다시 내려가면 강등 기록이 새 기간을 연다).</li>
 *   <li>부상자 명단·재활 경기 배정은 메이저 명단에 남아 있으니 마이너 기간이 아니다(결장 그대로).</li>
 * </ul>
 * 강등 규칙은 카드 상태(CardStatuses)와 같다.
 */
final class MinorStints {

    private static final Set<String> SENT_DOWN = Set.of("OPT", "OUT");
    private static final Set<String> CALLED_UP = Set.of("CU", "SE");
    /** 팀이 바뀌는 기록(CardStatuses와 같다) */
    private static final Set<String> CHANGES_TEAM = Set.of("TR", "CLW", "SFA", "SGN", "REL", "DFA");

    private MinorStints() {
    }

    /** 끝이 null이면 아직 마이너에 있다 */
    record Range(LocalDate start, LocalDate endExclusive) {

        boolean covers(LocalDate usDate) {
            return !usDate.isBefore(start) && (endExclusive == null || usDate.isBefore(endExclusive));
        }
    }

    /** @param movesOldestFirst 한 선수의 이동 기록(오래된 것부터, 같은 날이면 MLB 번호 순) */
    static List<Range> from(List<PlayerMove> movesOldestFirst) {
        List<Range> ranges = new ArrayList<>();
        LocalDate downSince = null;
        for (PlayerMove move : movesOldestFirst) {
            LocalDate date = move.getMoveDate();
            if (date == null) {
                continue;
            }
            if (downSince == null && sentDown(move)) {
                downSince = date;
            } else if (downSince != null && (CALLED_UP.contains(code(move)) || CHANGES_TEAM.contains(code(move)))) {
                if (date.isAfter(downSince)) {
                    ranges.add(new Range(downSince, date));
                }
                downSince = null;
            }
        }
        if (downSince != null) {
            ranges.add(new Range(downSince, null));
        }
        return ranges;
    }

    static boolean covers(List<Range> ranges, LocalDate usDate) {
        return ranges.stream().anyMatch(range -> range.covers(usDate));
    }

    private static boolean sentDown(PlayerMove move) {
        String code = code(move);
        return SENT_DOWN.contains(code)
                || ("ASG".equals(code) && !text(move).contains("rehab") && move.getToLevel() == Level.MINORS);
    }

    private static String code(PlayerMove move) {
        return move.getTypeCode() == null ? "" : move.getTypeCode().trim().toUpperCase(Locale.ROOT);
    }

    private static String text(PlayerMove move) {
        return move.getDescription() == null ? "" : move.getDescription().toLowerCase(Locale.ROOT);
    }
}
