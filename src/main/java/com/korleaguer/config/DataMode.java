package com.korleaguer.config;

import java.util.Locale;

public enum DataMode {
    SAMPLE("sample"),
    BDL("bdl");

    private final String value;

    DataMode(String value) {
        this.value = value;
    }

    /** API 응답의 dataMode 값("sample" / "bdl") */
    public String value() {
        return value;
    }

    /** 설정이 비어 있으면 API 키가 있을 때 bdl, 없을 때 sample */
    public static DataMode resolve(String configured, String apiKey) {
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        if (configured == null || configured.isBlank()) {
            return hasKey ? BDL : SAMPLE;
        }
        DataMode mode = switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "sample" -> SAMPLE;
            case "bdl" -> BDL;
            default -> throw new IllegalStateException("korleaguer.data.mode must be 'sample' or 'bdl': " + configured);
        };
        if (mode == BDL && !hasKey) {
            throw new IllegalStateException("korleaguer.data.mode=bdl requires the BALLDONTLIE_API_KEY environment variable");
        }
        return mode;
    }
}
