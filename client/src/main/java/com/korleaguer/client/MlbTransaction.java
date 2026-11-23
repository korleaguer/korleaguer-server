package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MLB 이동 기록 한 줄. date는 "2026-08-30" 같은 문자열 그대로 받아 둔다.
 * typeCode 예: OPT(옵션 강등), CU(콜업), OUT(아웃라이트), DES(지명 할당), TR(트레이드), SC(상태 변경), ASG(배정).
 * fromTeam은 없을 때가 많다(toTeam만 있는 줄).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbTransaction(
        Long id,
        MlbPerson person,
        String date,
        String effectiveDate,
        String typeCode,
        String typeDesc,
        String description,
        MlbTeam fromTeam,
        MlbTeam toTeam) {
}
