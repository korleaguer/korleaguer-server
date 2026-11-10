package com.korleaguer.client;

import java.time.LocalDate;
import java.util.List;

/**
 * MLB 공식 Stats API(statsapi.mlb.com)에서 필요한 호출만 모은 인터페이스. 키는 필요 없다.
 * 실제 HTTP 구현(HttpMlbClient)과 샘플 구현(SampleMlbClient)이 같은 모양으로 답한다.
 * 호출을 더할 때는 여기에 메서드를 두고, HttpMlbClient에서는 get(...) 도우미로 부른다(요청 제한·오류 처리가 같이 따라온다).
 * <p>
 * 모든 호출은 403/429면 MlbBlockedException(이번 실행의 MLB 호출을 멈추라는 뜻), 그 밖의 실패는 MlbApiException을 던진다.
 */
public interface MlbClient {

    /** 선수 한 명과 지금 소속 팀(currentTeam). 없으면 null */
    MlbPerson getPerson(long personId);

    /** 선수의 이동 기록(콜업·강등·부상자 명단 등). startDate~endDate(양 끝 포함) */
    List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate);

    /** 팀 한 곳과 그 팀의 리그 단계(sport). 없으면 null */
    MlbTeam getTeam(long teamId);

    /** 지금까지 보낸 요청 수(수집 기록용) */
    long requestCount();
}
