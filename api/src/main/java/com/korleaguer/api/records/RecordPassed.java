package com.korleaguer.api.records;

import java.time.LocalDate;

/** 이번 시즌 넘은 기록. gameId·dateKst는 넘은 경기의 번호와 한국 날짜, passedValue는 넘은 상대의 통산 값 */
public record RecordPassed(Long playerId, long mlbId, String nameKo, String photoUrl, String category, String label,
                     String unit, long gameId, LocalDate dateKst, String passedNameKo, int passedValue) {
}
