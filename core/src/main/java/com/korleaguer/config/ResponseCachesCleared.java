package com.korleaguer.config;

import java.util.List;

/** 화면용 응답 캐시를 지웠다(caches: 지운 캐시 이름). api.CacheWarmer가 듣고 다시 채운다. */
public record ResponseCachesCleared(String reason, List<String> caches) {
}
