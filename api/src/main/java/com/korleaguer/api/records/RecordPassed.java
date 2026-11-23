package com.korleaguer.api.records;

import java.time.LocalDate;

/** 이번 시즌 넘은 선배. dateKst는 넘은 경기의 한국 날짜, passedValue는 넘은 선배의 통산 값 */
public record RecordPassed(Long playerId, long mlbId, String nameKo, String photoUrl, String category, String label,
                     String unit, LocalDate dateKst, String passedNameKo, int passedValue) {
}
