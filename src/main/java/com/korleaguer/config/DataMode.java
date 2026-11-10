package com.korleaguer.config;

import java.util.Locale;

/** 데이터 출처. mlb: MLB 공식 Stats API(기본), sample: 네트워크 없이 샘플 데이터(테스트·오프라인) */
public enum DataMode {
    MLB("mlb"),
    SAMPLE("sample");

    private final String value;

    DataMode(String value) {
        this.value = value;
    }

    /** API 응답의 dataMode 값("mlb" / "sample") */
    public String value() {
        return value;
    }

    /** korleaguer.data-source 값. 비어 있으면 mlb */
    public static DataMode resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return MLB;
        }
        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "mlb" -> MLB;
            case "sample" -> SAMPLE;
            default -> throw new IllegalStateException("korleaguer.data-source must be 'mlb' or 'sample': " + configured);
        };
    }
}
