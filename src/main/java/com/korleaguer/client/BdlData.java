package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** balldontlie 단건 응답: { "data": {...} } */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BdlData<T>(T data) {
}
