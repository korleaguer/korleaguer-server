package com.korleaguer.client;

import com.korleaguer.support.RateLimiter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 실제 MLB Stats API(statsapi.mlb.com)를 부르는 클라이언트. User-Agent·제한 시간은 ClientConfig에서 정한다.
 * - 요청마다 RateLimiter에서 차례를 기다린다(기본 1초에 1회).
 * - 다시 시도는 하지 않는다. 실패한 건은 다음 수집 때 다시 묻는다.
 * - 404는 null, 403/429는 MlbBlockedException(이번 실행 중단), 그 밖의 실패는 MlbApiException이다.
 */
public class HttpMlbClient implements MlbClient {

    private final RestClient restClient;
    private final RateLimiter rateLimiter;
    private final AtomicLong requestCount = new AtomicLong();

    public HttpMlbClient(RestClient restClient, RateLimiter rateLimiter) {
        this.restClient = restClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public MlbPerson getPerson(long personId) {
        MlbResponses.People body = get("people/" + personId,
                uri -> uri.path("/api/v1/people/{id}").queryParam("hydrate", "currentTeam").build(personId),
                MlbResponses.People.class);
        return body == null || body.people() == null || body.people().isEmpty() ? null : body.people().getFirst();
    }

    @Override
    public List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate) {
        MlbResponses.Transactions body = get("transactions?playerId=" + personId,
                uri -> uri.path("/api/v1/transactions")
                        .queryParam("playerId", personId)
                        .queryParam("startDate", startDate)
                        .queryParam("endDate", endDate)
                        .build(),
                MlbResponses.Transactions.class);
        return body == null || body.transactions() == null ? List.of() : body.transactions();
    }

    @Override
    public MlbTeam getTeam(long teamId) {
        MlbResponses.Teams body = get("teams/" + teamId,
                uri -> uri.path("/api/v1/teams/{id}").build(teamId),
                MlbResponses.Teams.class);
        return body == null || body.teams() == null || body.teams().isEmpty() ? null : body.teams().getFirst();
    }

    @Override
    public long requestCount() {
        return requestCount.get();
    }

    /**
     * GET 한 번. 새 호출도 이 도우미로 부르면 요청 제한·요청 수 세기·오류 처리가 똑같이 붙는다.
     *
     * @param label 로그·예외 메시지에 쓸 짧은 이름
     * @return 응답 본문. 404면 null
     */
    <T> T get(String label, Function<UriBuilder, URI> uri, Class<T> type) {
        rateLimiter.acquire();
        requestCount.incrementAndGet();
        try {
            return restClient.get().uri(uri).retrieve().body(type);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.TooManyRequests e) {
            throw new MlbBlockedException("MLB refused " + label + ": " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new MlbApiException("MLB request failed: " + label + " (" + e.getMessage() + ")", e);
        }
    }
}
