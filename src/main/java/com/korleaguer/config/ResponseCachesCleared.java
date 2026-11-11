package com.korleaguer.config;

/** 화면용 응답 캐시(홈·선수·내 선수)를 지웠다. api.CacheWarmer가 듣고 다시 채운다. */
public record ResponseCachesCleared(String reason) {
}
