package com.korleaguer.ingest;

import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.MlbTransaction;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMove;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.Team;
import com.korleaguer.domain.TeamRepository;
import com.korleaguer.support.BirthDates;
import com.korleaguer.support.MlbTeams;
import com.korleaguer.support.PositionNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.korleaguer.ingest.MlbValues.date;
import static com.korleaguer.ingest.MlbValues.teamId;
import static com.korleaguer.ingest.MlbValues.teamName;

/**
 * 선수 줄과 MLB 선수 정보(프로필·지금 소속·이동 기록)를 DB에 upsert(있으면 고치고 없으면 넣기)한다.
 * HTTP 호출은 느리므로 수집기(PlayerRefresher)가 먼저 다 받아 오고, 저장만 여기서 트랜잭션으로 묶는다.
 * 값이 이상한 건(번호·날짜가 없음)은 그 건만 건너뛴다.
 */
@Component
@Transactional
public class PlayerWriter {

    private static final Logger log = LoggerFactory.getLogger(PlayerWriter.class);

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final AffiliationRepository affiliations;
    private final AppearanceRepository appearances;
    private final PlayerMoveRepository moves;

    public PlayerWriter(PlayerRepository players, TeamRepository teams, AffiliationRepository affiliations,
                        AppearanceRepository appearances, PlayerMoveRepository moves) {
        this.players = players;
        this.teams = teams;
        this.affiliations = affiliations;
        this.appearances = appearances;
        this.moves = moves;
    }

    /**
     * 명단의 선수 줄을 만들고 MLB 선수 번호를 맞춘다(받기 전에도 선수 목록에 보이도록).
     * 같은 번호를 다른 한글 이름이 갖고 있으면(명단에서 이름을 바꿈) 그 줄의 번호는 비운다.
     */
    public void ensurePlayer(String nameKo, Long mlbId) {
        Player player = players.findByNameKo(nameKo).orElseGet(() -> players.save(new Player(nameKo)));
        if (Objects.equals(player.getMlbId(), mlbId)) {
            return;
        }
        if (mlbId != null) {
            players.findByMlbId(mlbId).ifPresent(other -> {
                other.changeMlbId(null);
                players.flush();
            });
        }
        player.changeMlbId(mlbId);
    }

    /**
     * 선수 한 명의 MLB 정보(프로필·지금 소속)와 단계·이동 기록을 한 트랜잭션으로 저장한다.
     * 지금 소속은 MLB 구단이다. 마이너 팀에 있으면 그 팀의 MLB 구단(parentOrgId)으로 둔다.
     * MLB 구단 소속이 없으면(FA 등) 소속 줄을 지우지 않고 끝난 날을 남긴다(마지막 소속으로 화면에 남는다).
     * 이동 기록은 MLB 이동 기록 번호로 upsert하므로 같은 줄을 다시 받아도 늘지 않는다.
     *
     * @param teamLevels 팀 번호 → 단계. 모르는 팀은 넣지 않는다.
     * @return 저장한 이동 기록 수
     */
    public int savePlayer(long playerId, MlbPerson person, Level level, Instant checkedAt,
                          List<MlbTransaction> transactions, Map<Long, Level> teamLevels, LocalDate today) {
        Player player = players.findById(playerId).orElseThrow();
        player.update(person.fullName(),
                person.primaryPosition() == null ? null
                        : PositionNames.fromMlb(person.primaryPosition().abbreviation(), person.primaryPosition().name()),
                birthPlace(person), !Boolean.FALSE.equals(person.active()));
        player.updateProfile(birthDate(player.getNameKo(), person.birthDate()), person.height(),
                person.weight() == null ? null : String.valueOf(person.weight()), batsThrows(person),
                person.primaryNumber(), debutYear(person.mlbDebutDate()), person.currentAge());
        // MLB '지금 소속'은 FA를 선언해도 바로 비지 않는다. 이동 기록상 FA면 지금 소속이 없는 선수처럼 둔다
        // (단계·팀 이름 없음, 소속 줄은 끝난 것으로 남아 마지막 팀으로 보인다).
        boolean freeAgent = FreeAgency.isFreeAgent(transactions);
        MlbTeam current = freeAgent ? null : person.currentTeam();
        player.updateLevel(freeAgent ? null : level, current == null ? null : current.name(), checkedAt);
        player.updateFreeAgent(freeAgent);
        saveAffiliation(player, current, today);

        int saved = 0;
        for (MlbTransaction source : transactions) {
            LocalDate date = date(source.date());
            if (source.id() == null || date == null) {
                log.debug("skip MLB move with missing id or date: {} {}", source.id(), source.date());
                continue;
            }
            PlayerMove move = moves.findById(source.id()).orElseGet(() -> new PlayerMove(source.id(), player));
            Long fromId = teamId(source.fromTeam());
            Long toId = teamId(source.toTeam());
            move.update(player, date, source.typeCode(), source.typeDesc(), source.description(),
                    fromId, teamName(source.fromTeam()), fromId == null ? null : teamLevels.get(fromId),
                    toId, teamName(source.toTeam()), toId == null ? null : teamLevels.get(toId));
            moves.save(move);
            saved++;
        }
        return saved;
    }

    /**
     * 소속 줄이 없는 선수의 마지막으로 뛴 경기의 팀을 끝난 소속으로 남긴다. 소속 줄이 이미 있으면 아무것도 하지 않는다.
     * 처음 받을 때 이미 MLB 구단 소속이 없던 선수(FA 등)의 시즌 기록을 저장한 뒤 GameWriter가 부른다.
     */
    public void keepLastPlayedTeamIfUnaffiliated(Player player, LocalDate today) {
        if (affiliations.findByPlayer(player).isEmpty()) {
            keepLastPlayedTeam(player, today);
        }
    }

    private void saveAffiliation(Player player, MlbTeam current, LocalDate today) {
        Long clubId = clubId(current);
        Optional<Affiliation> existing = affiliations.findByPlayer(player);
        if (clubId == null) {
            // 소속이 없어졌으면(FA 등) 줄을 지우지 않고 끝난 날만 남긴다. 시즌 기록이 있는 선수가 홈에서 빠지지 않게 한다.
            // 끝난 소속의 구단 경기는 결장·마지막 경기일로 보지 않고(RosterMember.majorTeam) 경기 갱신에서도 뺀다(findAllCurrent).
            existing.ifPresentOrElse(a -> a.end(today), () -> keepLastPlayedTeam(player, today));
            return;
        }
        Team team = teams.findById(clubId).orElseGet(() -> new Team(clubId));
        if (MlbTeams.isClub(current.id())) {
            team.update(MlbTeams.abbreviation(clubId), current.name());
        } else if (team.getAbbreviation() == null) {
            team.update(MlbTeams.abbreviation(clubId), team.getNameEn());
        }
        Team saved = teams.save(team);
        existing.ifPresentOrElse(a -> a.changeTeam(saved), () -> affiliations.save(new Affiliation(player, saved, today)));
    }

    // 소속 줄이 없는 선수의 마지막으로 뛴 경기의 팀을 끝난 소속으로 남긴다. 뛴 기록이 없으면 남기지 않는다.
    private void keepLastPlayedTeam(Player player, LocalDate today) {
        appearances.findFirstByPlayerOrderByGameStartTimeUtcDesc(player).ifPresent(last -> {
            Affiliation affiliation = new Affiliation(player, last.getTeam(), today);
            affiliation.end(today);
            affiliations.save(affiliation);
        });
    }

    // MLB 구단이면 그 번호, 마이너 팀이면 그 팀의 MLB 구단 번호. 둘 다 아니면 null
    private static Long clubId(MlbTeam current) {
        if (current == null || current.id() == null) {
            return null;
        }
        if (MlbTeams.isClub(current.id())) {
            return current.id();
        }
        return MlbTeams.isClub(current.parentOrgId()) ? current.parentOrgId() : null;
    }

    // "Seoul, Republic of Korea" / "Fort Lauderdale, FL, USA"
    private static String birthPlace(MlbPerson person) {
        String place = Stream.of(person.birthCity(), person.birthStateProvince(), person.birthCountry())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(", "));
        return place.isEmpty() ? null : place;
    }

    // "Left/Right"(타격/투구). 한쪽이라도 없으면 null
    private static String batsThrows(MlbPerson person) {
        if (person.batSide() == null || person.pitchHand() == null
                || person.batSide().description() == null || person.pitchHand().description() == null) {
            return null;
        }
        return person.batSide().description() + "/" + person.pitchHand().description();
    }

    private static Integer debutYear(String mlbDebutDate) {
        LocalDate date = date(mlbDebutDate);
        return date == null ? null : date.getYear();
    }

    // 읽을 수 없는 생년월일은 WARN 한 줄을 남기고 비운다(없는 값은 조용히 비운다).
    private static LocalDate birthDate(String nameKo, String text) {
        LocalDate birthDate = BirthDates.parse(text);
        if (birthDate == null && text != null && !text.isBlank()) {
            log.warn("[player] {}: unreadable birthDate '{}', birth_date stays empty", nameKo, text);
        }
        return birthDate;
    }
}
