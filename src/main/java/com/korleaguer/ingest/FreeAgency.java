package com.korleaguer.ingest;

import com.korleaguer.client.MlbTransaction;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MLB 이동 기록으로 지금 FA인지 본다.
 * MLB의 '지금 소속'(currentTeam)은 선수가 FA를 선언해도 바로 비지 않으므로(예: 고우석은 2026-08-30 FA 선언 뒤에도
 * St. Paul Saints로 남는다) 이동 기록을 더 믿는다. 한국 팀 등 MLB 밖 계약은 MLB 기록에 남지 않아서 여기서는 FA까지만 안다.
 */
final class FreeAgency {

    /** FA 선언(Declared Free Agency). 지명 할당(Designated for Assignment)은 DES로 다르다. */
    static final String DECLARED = "DFA";
    private static final String RELEASED = "REL";
    private static final Set<String> SIGNINGS = Set.of("SFA", "SGN");

    private FreeAgency() {
    }

    /**
     * 마지막 FA 선언 뒤에 새 팀으로 가는 기록이 없으면 FA다.
     * <ul>
     *   <li>뒤 날짜의 기록은 방출(REL)·또 한 번의 FA 선언 말고는 모두 새 팀이 생긴 것으로 본다(계약·트레이드·클레임·콜업 등).</li>
     *   <li>같은 날 기록은 계약만 새 팀으로 본다. 같은 날 아웃라이트(OUT)를 거부하고 FA를 선언하는 경우가 흔해서다.</li>
     * </ul>
     */
    static boolean isFreeAgent(List<MlbTransaction> moves) {
        LocalDate declared = moves.stream()
                .filter(m -> DECLARED.equals(m.typeCode()))
                .map(FreeAgency::date)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (declared == null) {
            return false;
        }
        return moves.stream().noneMatch(m -> joinsTeamAfter(m, declared));
    }

    private static boolean joinsTeamAfter(MlbTransaction move, LocalDate declared) {
        LocalDate date = date(move);
        if (date == null || date.isBefore(declared)) {
            return false;
        }
        if (date.isAfter(declared)) {
            return !RELEASED.equals(move.typeCode()) && !DECLARED.equals(move.typeCode());
        }
        return isSigning(move);
    }

    private static boolean isSigning(MlbTransaction move) {
        return SIGNINGS.contains(move.typeCode()) || (move.typeDesc() != null && move.typeDesc().contains("Signed"));
    }

    private static LocalDate date(MlbTransaction move) {
        try {
            return move.date() == null ? null : LocalDate.parse(move.date());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
