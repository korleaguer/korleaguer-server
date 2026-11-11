package com.korleaguer.ingest;

/**
 * 따라잡기(12분마다 스케줄, mlb에서 앱 시작 때)가 끝났다. 수집이 데이터를 바꿨으면 캐시는 이미 비워졌다.
 * 화면용 응답을 미리 채우는 쪽(api.CacheWarmer)이 듣고, 시각으로 바뀌는 값·수집 밖의 쓰기도 이때 본다.
 * 샘플 데이터(테스트)에서는 보내지 않는다.
 */
public record CatchUpFinished() {
}
