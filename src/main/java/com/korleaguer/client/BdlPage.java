package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** balldontlie 목록 응답: { "data": [...], "meta": { "next_cursor": ... } } */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BdlPage<T>(List<T> data, Meta meta) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(Long nextCursor, Integer perPage) {
    }

    /** 다음 페이지 커서. 마지막 페이지면 null */
    public Long nextCursor() {
        return meta == null ? null : meta.nextCursor();
    }
}
