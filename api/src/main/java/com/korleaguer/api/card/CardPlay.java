package com.korleaguer.api.card;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 출전 줄. 타자는 recent(지금 상태가 시작된 뒤 팀의 끝난 경기 최근 10개, 오래된 것부터 STARTER·SUB·PLAYED·ABSENT,
 * PLAYED는 선발·교체를 모르는 출전), after는 그 경기들이 복귀·콜업 뒤 경기라 10개가 안 될 때 "RETURNED"·"CALLED_UP"이다.
 * 투수는 appearances14(최근 14일 등판 수)와 daysSinceLast(마지막 등판이 며칠 전인지, 등판이 없으면 null)다.
 * 메이저에 있을 때만 있다.
 */
public record CardPlay(
        @ArraySchema(schema = @Schema(allowableValues = {"STARTER", "SUB", "PLAYED", "ABSENT"}))
        @Nullable List<String> recent,
        @Schema(allowableValues = {"RETURNED", "CALLED_UP"}) @Nullable String after,
        @Nullable Integer appearances14,
        @Nullable Integer daysSinceLast) {
}
