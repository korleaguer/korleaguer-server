package com.korleaguer.ingest;

import com.korleaguer.client.BdlGame;
import com.korleaguer.client.BdlPlayer;
import com.korleaguer.client.BdlStats;
import com.korleaguer.client.BdlTeam;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.Team;
import com.korleaguer.domain.TeamRepository;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.BirthDates;
import com.korleaguer.support.KoreanTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * balldontlie 응답을 DB에 upsert(있으면 고치고 없으면 넣기)한다.
 * HTTP 호출은 느리므로 IngestService가 먼저 다 받아 오고, 저장만 여기서 트랜잭션으로 묶는다.
 */
@Component
@Transactional
public class IngestWriter {

    private static final Logger log = LoggerFactory.getLogger(IngestWriter.class);

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final AffiliationRepository affiliations;
    private final GameRepository games;
    private final AppearanceRepository appearances;

    public IngestWriter(TeamRepository teams, PlayerRepository players, AffiliationRepository affiliations,
                        GameRepository games, AppearanceRepository appearances) {
        this.teams = teams;
        this.players = players;
        this.affiliations = affiliations;
        this.games = games;
        this.appearances = appearances;
    }

    /** 매칭 전에도 선수 목록에 보이도록 한글 이름만 있는 줄을 만들어 둔다. */
    public void ensurePlayer(String nameKo) {
        if (players.findByNameKo(nameKo).isEmpty()) {
            players.save(new Player(nameKo));
        }
    }

    public void savePlayer(String nameKo, BdlPlayer source, LocalDate today) {
        Player player = players.findByNameKo(nameKo).orElseGet(() -> players.save(new Player(nameKo)));
        player.update(source.fullName(), source.position(), source.id(), source.birthPlace(),
                !Boolean.FALSE.equals(source.active()));
        player.updateProfile(birthDate(nameKo, source.dob()), source.height(), source.weight(), source.batsThrows(),
                source.jersey(), source.debutYear(), source.age());

        if (!hasId(source.team())) {
            return;
        }
        Team team = saveTeam(source.team());
        affiliations.findByPlayerAndEndDateIsNull(player).ifPresentOrElse(
                current -> current.changeTeam(team),
                () -> affiliations.save(new Affiliation(player, team, today)));
    }

    /**
     * 경기와 선수 기록을 한 트랜잭션으로 저장한다. 도중에 실패하면 둘 다 저장되지 않는다.
     * 값이 이상한 건(필수 값 null, 읽을 수 없는 날짜, DB에 없는 경기·선수)은 그 건만 건너뛰고 센다.
     */
    public SaveResult saveAll(List<BdlGame> gameSources, List<BdlStats> statSources) {
        int savedGames = 0;
        for (BdlGame source : gameSources) {
            if (saveGame(source)) {
                savedGames++;
            }
        }
        int savedStats = 0;
        for (BdlStats source : statSources) {
            if (saveStat(source)) {
                savedStats++;
            }
        }
        SaveResult result = new SaveResult(savedGames, gameSources.size() - savedGames,
                savedStats, statSources.size() - savedStats);
        if (result.skippedGames() > 0 || result.skippedStats() > 0) {
            log.warn("skipped {} games and {} stats (missing values or unknown game/player)",
                    result.skippedGames(), result.skippedStats());
        }
        return result;
    }

    private boolean saveGame(BdlGame source) {
        if (source.id() == null || source.season() == null || source.date() == null
                || !hasId(source.homeTeam()) || !hasId(source.awayTeam())) {
            log.debug("skip game with missing values: {}", source.id());
            return false;
        }
        Instant start;
        try {
            start = KoreanTime.parseUtc(source.date());
        } catch (DateTimeParseException e) {
            log.debug("skip game {} with unreadable date '{}'", source.id(), source.date());
            return false;
        }
        Game game = games.findById(source.id()).orElseGet(() -> new Game(source.id()));
        game.update(
                source.season(),
                source.seasonType(),
                start,
                saveTeam(source.homeTeam()),
                saveTeam(source.awayTeam()),
                runs(source.homeTeamData()),
                runs(source.awayTeamData()),
                Objects.requireNonNullElse(source.statusState(), "unknown"),
                source.venue());
        games.save(game);
        return true;
    }

    private boolean saveStat(BdlStats source) {
        if (source.player() == null || source.player().id() == null || source.gameId() == null
                || !hasId(source.team())) {
            log.debug("skip stats with missing values: game={}", source.gameId());
            return false;
        }
        Optional<Player> player = players.findByBdlPlayerId(source.player().id());
        Optional<Game> game = games.findById(source.gameId());
        if (player.isEmpty() || game.isEmpty()) {
            log.debug("skip stats: player={}, game={} not in DB", source.player().id(), source.gameId());
            return false;
        }
        Appearance appearance = appearances.findByPlayerAndGame(player.get(), game.get())
                .orElseGet(() -> new Appearance(player.get(), game.get()));
        appearance.update(saveTeam(source.team()), toBatting(source), toPitching(source));
        appearances.save(appearance);
        return true;
    }

    private Team saveTeam(BdlTeam source) {
        Team team = teams.findById(source.id()).orElseGet(() -> new Team(source.id()));
        team.update(source.abbreviation(), source.displayName(), source.location(), source.league());
        return teams.save(team);
    }

    // 읽을 수 없는 생년월일은 WARN 한 줄을 남기고 비운다(없는 값은 조용히 비운다).
    private static LocalDate birthDate(String nameKo, String dob) {
        LocalDate birthDate = BirthDates.parse(dob);
        if (birthDate == null && dob != null && !dob.isBlank()) {
            log.warn("[player] {}: unreadable dob '{}', birth_date stays empty", nameKo, dob);
        }
        return birthDate;
    }

    private static boolean hasId(BdlTeam team) {
        return team != null && team.id() != null;
    }

    private static Integer runs(BdlGame.TeamData data) {
        return data == null ? null : data.runs();
    }

    private static Batting toBatting(BdlStats s) {
        return new Batting(s.atBats(), s.runs(), s.hits(), s.doubles(), s.triples(), s.hr(), s.rbi(), s.bb(), s.k(),
                s.hitByPitch(), s.stolenBases(), s.sacFlies(), s.plateAppearances(), s.totalBases());
    }

    // 아웃 수는 pitching_outs를 쓰고, 없으면 ip(5.2 = 17아웃)로 구한다.
    private static Pitching toPitching(BdlStats s) {
        Integer outs = s.pitchingOuts() != null ? s.pitchingOuts() : BaseballMath.outsFromIp(s.ip());
        return new Pitching(outs, s.pHits(), s.pRuns(), s.er(), s.pBb(), s.pK(), s.pHr(), s.pitchCount(),
                s.wins(), s.losses(), s.saves());
    }

    public record SaveResult(int savedGames, int skippedGames, int savedStats, int skippedStats) {
    }
}
