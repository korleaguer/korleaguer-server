package com.korleaguer.api.records;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 한 기록의 통산 순위. group: HITTING / PITCHING */
public record RecordCategory(
        String key,
        String label,
        @Schema(allowableValues = {"HITTING", "PITCHING"}) String group,
        String unit,
        List<RecordLeader> leaders) {
}
