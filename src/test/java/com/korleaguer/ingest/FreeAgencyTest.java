package com.korleaguer.ingest;

import com.korleaguer.client.MlbTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 마지막 FA 선언 뒤에 새 팀 기록이 없으면 FA다. */
class FreeAgencyTest {

    @Test
    void declaredAfterRefusingOutrightIsFreeAgent() {
        // 고우석 2026: 지명 할당 → 같은 날 아웃라이트와 FA 선언(아웃라이트를 거부)
        assertThat(FreeAgency.isFreeAgent(List.of(
                move("2026-08-28", "DES", "Designated for Assignment"),
                move("2026-08-30", "DFA", "Declared Free Agency"),
                move("2026-08-30", "OUT", "Outrighted")))).isTrue();
    }

    @Test
    void declaredAfterSeasonIsFreeAgent() {
        // 배지환 2026: 시즌 중 아웃라이트, 시즌 뒤 FA 선언
        assertThat(FreeAgency.isFreeAgent(List.of(
                move("2026-08-19", "OUT", "Outrighted"),
                move("2026-11-02", "DFA", "Declared Free Agency")))).isTrue();
    }

    @Test
    void signingAfterDeclaringEndsFreeAgency() {
        assertThat(FreeAgency.isFreeAgent(List.of(
                move("2026-11-02", "DFA", "Declared Free Agency"),
                move("2026-12-10", "SFA", "Signed as Free Agent")))).isFalse();
        // 같은 날 계약도 새 팀이다
        assertThat(FreeAgency.isFreeAgent(List.of(
                move("2026-11-02", "DFA", "Declared Free Agency"),
                move("2026-11-02", "SGN", "Signed")))).isFalse();
        // 표에 없는 종류라도 typeDesc에 Signed가 있으면 계약이다
        assertThat(FreeAgency.isFreeAgent(List.of(
                move("2026-11-02", "DFA", "Declared Free Agency"),
                move("2026-11-02", "XYZ", "Signed to Minor League Contract")))).isFalse();
    }

    @Test
    void anyLaterMoveExceptReleaseMeansNewTeam() {
        assertThat(FreeAgency.isFreeAgent(List.of(
                move("2026-08-30", "DFA", "Declared Free Agency"),
                move("2026-09-03", "TR", "Trade")))).isFalse();
        assertThat(FreeAgency.isFreeAgent(List.of(
                move("2026-08-30", "DFA", "Declared Free Agency"),
                move("2026-09-03", "REL", "Released")))).isTrue();
    }

    @Test
    void latestDeclarationCounts() {
        // 지난해 FA 선언 → 계약 → 올해 다시 FA 선언
        assertThat(FreeAgency.isFreeAgent(List.of(
                move("2025-11-03", "DFA", "Declared Free Agency"),
                move("2026-01-15", "SFA", "Signed as Free Agent"),
                move("2026-11-02", "DFA", "Declared Free Agency")))).isTrue();
    }

    @Test
    void noDeclarationIsNotFreeAgent() {
        assertThat(FreeAgency.isFreeAgent(List.of(move("2026-08-19", "OUT", "Outrighted")))).isFalse();
        assertThat(FreeAgency.isFreeAgent(List.of())).isFalse();
        // 날짜를 읽을 수 없는 FA 선언은 세지 않는다
        assertThat(FreeAgency.isFreeAgent(List.of(move("?", "DFA", "Declared Free Agency")))).isFalse();
    }

    private static MlbTransaction move(String date, String typeCode, String typeDesc) {
        return new MlbTransaction(1L, null, date, date, typeCode, typeDesc, null, null, null);
    }
}
