package com.korleaguer.ingest;

import com.korleaguer.client.MlbTransaction;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MLB 이동 기록으로 지금 FA인지 본다(FA 선언이나 방출 뒤에 계약 등 새 팀 기록이 없음).
 * MLB의 '지금 소속'(currentTeam)은 선수가 FA를 선언해도 바로 비지 않으므로(예: 고우석은 2026-08-30 FA 선언 뒤에도
 * St. Paul Saints로 남는다) 이동 기록을 더 믿는다. 한국 팀 등 MLB 밖 계약은 MLB 기록에 남지 않아서 여기서는 FA까지만 안다.
 */
final class FreeAgency {

    /** FA 선언(Declared Free Agency). 지명 할당(Designated for Assignment)은 DES로 다르다. */
    static final String DECLARED = "DFA";
    /** 방출(Released). 방출된 선수도 계약할 때까지 FA다. */
    static final String RELEASED = "REL";
    /** FA가 되는 기록 */
    private static final Set<String> BECOMES_FREE_AGENT = Set.of(DECLARED, RELEASED);
    /** 팀이 생기지 않는 기록(상태 변경·출장 정지·등번호). 대표팀 소집 같은 상태 변경도 여기에 든다. */
    private static final Set<String> NO_NEW_TEAM = Set.of("SC", "SU", "NUM");
    private static final Set<String> SIGNINGS = Set.of("SFA", "SGN");

    private FreeAgency() {
    }

    /**
     * 마지막 FA 선언·방출 뒤에 새 팀이 생기는 기록이 없으면 FA다.
     * <ul>
     *   <li>뒤 날짜의 기록은 FA 선언·방출·상태 변경·출장 정지·등번호 변경 말고는 모두 새 팀이 생긴 것으로 본다
     *       (계약·트레이드·클레임·콜업 등).</li>
     *   <li>같은 날 기록은 계약만 새 팀으로 본다. 같은 날 아웃라이트(OUT)를 거부하고 FA를 선언하는 경우가 흔해서다.</li>
     * </ul>
     */
    static boolean isFreeAgent(List<MlbTransaction> moves) {
        LocalDate since = moves.stream()
                .filter(m -> BECOMES_FREE_AGENT.contains(m.typeCode()))
                .map(FreeAgency::date)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (since == null) {
            return false;
        }
        return moves.stream().noneMatch(m -> joinsTeamAfter(m, since));
    }

    private static boolean joinsTeamAfter(MlbTransaction move, LocalDate since) {
        LocalDate date = date(move);
        if (date == null || date.isBefore(since)) {
            return false;
        }
        if (date.isAfter(since)) {
            return isSigning(move)
                    || (!BECOMES_FREE_AGENT.contains(move.typeCode()) && !NO_NEW_TEAM.contains(move.typeCode()));
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
