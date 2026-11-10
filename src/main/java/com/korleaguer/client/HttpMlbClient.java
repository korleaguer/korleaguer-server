package com.korleaguer.client;

import com.korleaguer.support.RateLimiter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 실제 MLB Stats API(statsapi.mlb.com)를 부르는 클라이언트. User-Agent·제한 시간은 ClientConfig에서 정한다.
 * - 요청마다 RateLimiter에서 차례를 기다린다(기본 1초에 1회).
 * - 다시 시도는 하지 않는다. 실패한 건은 다음 수집 때 다시 묻는다.
 * - 404는 null(목록이면 빈 목록), 403/429는 MlbBlockedException(이번 실행 중단), 그 밖의 실패는 MlbApiException이다.
 */
public class HttpMlbClient implements MlbClient {

    private static final int MLB = 1;

    private final RestClient restClient;
    private final RateLimiter rateLimiter;
    private final AtomicLong requestCount = new AtomicLong();

    public HttpMlbClient(RestClient restClient, RateLimiter rateLimiter) {
        this.restClient = restClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public List<MlbPerson> getPeople(Collection<Long> personIds) {
        if (personIds.isEmpty()) {
            return List.of();
        }
        MlbResponses.People body = get("people",
                uri -> uri.path("/api/v1/people")
                        .queryParam("personIds", joined(personIds))
                        .queryParam("hydrate", "currentTeam")
                        .build(),
                MlbResponses.People.class);
        return body == null || body.people() == null ? List.of() : body.people();
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
    public List<MlbGameLogSplit> getGameLog(long personId, Group group, int season) {
        MlbResponses.Stats body = get("people/" + personId + "/stats gameLog " + group.value(),
                uri -> uri.path("/api/v1/people/{id}/stats")
                        .queryParam("stats", "gameLog")
                        .queryParam("group", group.value())
                        .queryParam("season", season)
                        .build(personId),
                MlbResponses.Stats.class);
        if (body == null || body.stats() == null) {
            return List.of();
        }
        return body.stats().stream()
                .filter(statGroup -> statGroup.splits() != null)
                .flatMap(statGroup -> statGroup.splits().stream())
                .toList();
    }

    @Override
    public MlbMinorGames getMinorGames(long personId, int season) {
        MlbResponses.SeasonStats body = get("people/" + personId + "/stats season minors",
                uri -> uri.path("/api/v1/people/{id}/stats")
                        .queryParam("stats", "season")
                        .queryParam("group", "hitting,pitching")
                        .queryParam("season", season)
                        .queryParam("leagueListId", "milb_all")
                        .build(personId),
                MlbResponses.SeasonStats.class);
        if (body == null || body.stats() == null) {
            return MlbMinorGames.NONE;
        }
        int hitting = 0;
        int pitching = 0;
        for (MlbResponses.SeasonGroup group : body.stats()) {
            if ("hitting".equals(group.groupName())) {
                hitting += teamGames(group);
            } else if ("pitching".equals(group.groupName())) {
                pitching += teamGames(group);
            }
        }
        return new MlbMinorGames(hitting, pitching);
    }

    /**
     * 팀별 줄의 경기 수만 더한다. 단계 합계·마이너 전체 합계 줄은 팀이 없어서 빠지므로 두 번 세지 않는다.
     * 트리플A ~ 루키(sport.id 11~16, Level.fromSportId와 같은 범위)만 센다.
     */
    private static int teamGames(MlbResponses.SeasonGroup group) {
        if (group.splits() == null) {
            return 0;
        }
        return group.splits().stream()
                .filter(split -> split.team() != null && split.team().id() != null)
                .filter(split -> split.sport() != null && split.sport().id() != null
                        && split.sport().id() >= 11 && split.sport().id() <= 16)
                .mapToInt(split -> split.stat() == null || split.stat().gamesPlayed() == null
                        ? 0 : split.stat().gamesPlayed())
                .sum();
    }

    @Override
    public List<MlbScheduleGame> getSeasonSchedule(Collection<Long> teamIds, int season) {
        if (teamIds.isEmpty()) {
            return List.of();
        }
        return schedule("schedule season " + season, uri -> uri.path("/api/v1/schedule")
                .queryParam("sportId", MLB)
                .queryParam("teamId", joined(teamIds))
                .queryParam("season", season)
                .build());
    }

    @Override
    public List<MlbScheduleGame> getSchedule(Collection<Long> teamIds, LocalDate startDate, LocalDate endDate) {
        if (teamIds.isEmpty()) {
            return List.of();
        }
        return schedule("schedule " + startDate + "~" + endDate, uri -> uri.path("/api/v1/schedule")
                .queryParam("sportId", MLB)
                .queryParam("teamId", joined(teamIds))
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate)
                .build());
    }

    @Override
    public MlbBoxscore getBoxscore(long gamePk) {
        return get("game/" + gamePk + "/boxscore",
                uri -> uri.path("/api/v1/game/{gamePk}/boxscore").build(gamePk),
                MlbBoxscore.class);
    }

    @Override
    public long requestCount() {
        return requestCount.get();
    }

    private List<MlbScheduleGame> schedule(String label, Function<UriBuilder, URI> uri) {
        MlbResponses.Schedule body = get(label, uri, MlbResponses.Schedule.class);
        if (body == null || body.dates() == null) {
            return List.of();
        }
        return body.dates().stream()
                .filter(date -> date.games() != null)
                .flatMap(date -> date.games().stream())
                .toList();
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

    private static String joined(Collection<Long> ids) {
        return ids.stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
}
