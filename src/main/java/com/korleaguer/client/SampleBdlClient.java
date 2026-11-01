package com.korleaguer.client;

import com.korleaguer.support.KoreanTime;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * API 키가 없을 때 쓰는 샘플 클라이언트.
 * resources/sample/*.json(balldontlie 응답과 같은 모양)을 읽어 실제 API처럼 걸러서 돌려준다.
 */
public class SampleBdlClient implements BdlClient {

    private final List<BdlPlayer> players;
    private final List<BdlGame> games;
    private final List<BdlStats> stats;
    private final Map<Long, BdlGame> gamesById;
    private final AtomicLong requestCount = new AtomicLong();

    public SampleBdlClient(JsonMapper jsonMapper) {
        this(read(jsonMapper, "sample/players.json", new TypeReference<BdlPage<BdlPlayer>>() {
                }),
                read(jsonMapper, "sample/games.json", new TypeReference<BdlPage<BdlGame>>() {
                }),
                read(jsonMapper, "sample/stats.json", new TypeReference<BdlPage<BdlStats>>() {
                }));
    }

    /** 데이터를 직접 넣는 생성자(테스트에서 이적 같은 상황을 만들 때 쓴다) */
    public SampleBdlClient(List<BdlPlayer> players, List<BdlGame> games, List<BdlStats> stats) {
        this.players = players;
        this.games = games;
        this.stats = stats;
        this.gamesById = games.stream().collect(Collectors.toMap(BdlGame::id, Function.identity()));
    }

    @Override
    public List<BdlPlayer> searchPlayers(String search) {
        requestCount.incrementAndGet();
        String query = search.toLowerCase(Locale.ROOT);
        return players.stream()
                .filter(p -> p.fullName().toLowerCase(Locale.ROOT).contains(query))
                .toList();
    }

    @Override
    public BdlPlayer getPlayer(long id) {
        requestCount.incrementAndGet();
        return players.stream().filter(p -> p.id() == id).findFirst().orElse(null);
    }

    @Override
    public List<BdlGame> getGames(List<Long> teamIds, List<Integer> seasons, List<LocalDate> dates) {
        requestCount.incrementAndGet();
        return games.stream()
                .filter(g -> teamIds.isEmpty()
                        || teamIds.contains(g.homeTeam().id()) || teamIds.contains(g.awayTeam().id()))
                .filter(g -> seasons.isEmpty() || seasons.contains(g.season()))
                .filter(g -> dates.isEmpty() || dates.contains(utcDate(g)))
                .toList();
    }

    @Override
    public List<BdlStats> getStats(List<Long> playerIds, List<Integer> seasons, List<Long> gameIds) {
        requestCount.incrementAndGet();
        return stats.stream()
                .filter(s -> playerIds.isEmpty() || playerIds.contains(s.player().id()))
                .filter(s -> gameIds.isEmpty() || gameIds.contains(s.gameId()))
                .filter(s -> seasons.isEmpty() || seasonOf(s) == null || seasons.contains(seasonOf(s)))
                .toList();
    }

    @Override
    public long requestCount() {
        return requestCount.get();
    }

    // 경기 목록에 없는 game_id면 null. 그런 기록도 돌려준다(실제 API에서 생길 수 있는 상황).
    private Integer seasonOf(BdlStats stat) {
        BdlGame game = gamesById.get(stat.gameId());
        return game == null ? null : game.season();
    }

    private static LocalDate utcDate(BdlGame game) {
        return KoreanTime.parseUtc(game.date()).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static <T> List<T> read(JsonMapper jsonMapper, String path, TypeReference<BdlPage<T>> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return jsonMapper.readValue(in, type).data();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
