package com.korleaguer.api.records;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 한 시즌 최다 */
public record RecordBest(
        String key,
        String label,
        @Schema(allowableValues = {"HITTING", "PITCHING"}) String group,
        String unit,
        List<RecordSeasonRow> rows) {
}
