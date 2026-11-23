package com.korleaguer.api.records;

import java.util.List;

/** 한 시즌 최다 */
public record RecordBest(String key, String label, String group, String unit, List<RecordSeasonRow> rows) {
}
