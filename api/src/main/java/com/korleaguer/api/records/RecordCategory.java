package com.korleaguer.api.records;

import java.util.List;

/** 한 기록의 통산 순위. group: HITTING / PITCHING */
public record RecordCategory(String key, String label, String group, String unit, List<RecordLeader> leaders) {
}
