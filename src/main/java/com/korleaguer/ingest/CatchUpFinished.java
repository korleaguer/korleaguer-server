package com.korleaguer.ingest;

/**
 * 따라잡기(12분마다 스케줄, mlb에서 앱 시작 때)가 끝났다. 이때 수집은 캐시를 비운 뒤다.
 * 화면용 응답을 미리 채우는 쪽(api.CacheWarmer)이 듣는다. 샘플 데이터(테스트)에서는 보내지 않는다.
 */
public record CatchUpFinished() {
}
