package com.korleaguer.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonRepository;
import com.korleaguer.domain.Team;
import com.korleaguer.domain.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MLB 선수 정보(people)를 선수 프로필과 지금 소속으로 저장한다. */
class IngestWriterProfileTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    private final PlayerRepository players = mock(PlayerRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final AffiliationRepository affiliations = mock(AffiliationRepository.class);
    private final IngestWriter writer = new IngestWriter(teams, players, affiliations, mock(GameRepository.class),
            mock(AppearanceRepository.class), mock(PlayerMoveRepository.class), mock(PlayerSeasonRepository.class));
    private final Player player = new Player("이정후");

    private final Logger writerLog = (Logger) LoggerFactory.getLogger(IngestWriter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(players.findById(1L)).thenReturn(Optional.of(player));
        when(teams.findById(any())).thenReturn(Optional.empty());
        when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(affiliations.findByPlayerAndEndDateIsNull(player)).thenReturn(Optional.empty());
        logs.start();
        writerLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        writerLog.detachAppender(logs);
    }

    @Test
    void profileFieldsAreSaved() {
        save(person("1998-08-20", new MlbTeam(137L, "San Francisco Giants", null, null, null)));

        assertThat(player.getNameEn()).isEqualTo("Jung Hoo Lee");
        assertThat(player.getPosition()).isEqualTo("Right Fielder"); // MLB 약어 RF → 영어 이름
        assertThat(player.getBirthPlace()).isEqualTo("Seoul, Republic of Korea");
        assertThat(player.getBirthDate()).isEqualTo(LocalDate.of(1998, 8, 20));
        assertThat(player.getHeight()).isEqualTo("6' 0\"");
        assertThat(player.getWeight()).isEqualTo("196");
        assertThat(player.getBatsThrows()).isEqualTo("Left/Right");
        assertThat(player.getJersey()).isEqualTo("51");
        assertThat(player.getDebutYear()).isEqualTo(2024);
        assertThat(player.getAge()).isEqualTo(28);
        assertThat(player.getLevelTeamName()).isEqualTo("San Francisco Giants");
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void minorLeagueTeamMeansItsMlbClub() {
        save(person("1998-08-20", new MlbTeam(1960L, "St. Paul Saints", null, null, 142L)));

        ArgumentCaptor<Affiliation> saved = ArgumentCaptor.forClass(Affiliation.class);
        verify(affiliations).save(saved.capture());
        Team team = saved.getValue().getTeam();
        assertThat(team.getMlbTeamId()).isEqualTo(142L);
        assertThat(team.getAbbreviation()).isEqualTo("MIN");
        assertThat(player.getLevelTeamName()).isEqualTo("St. Paul Saints");
    }

    @Test
    void noTeamRemovesCurrentAffiliation() {
        Affiliation current = new Affiliation(player, new Team(137L), TODAY.minusDays(30));
        when(affiliations.findByPlayerAndEndDateIsNull(player)).thenReturn(Optional.of(current));

        save(person("1998-08-20", null));

        verify(affiliations).delete(current);
    }

    @Test
    void unreadableBirthDateIsLeftEmptyWithOneWarning() {
        save(person("sometime in 1998", null));

        assertThat(player.getBirthDate()).isNull();
        assertThat(player.getHeight()).isEqualTo("6' 0\"");
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("이정후").contains("sometime in 1998"));
    }

    @Test
    void missingBirthDateIsLeftEmptyWithoutWarning() {
        save(person(null, null));

        assertThat(player.getBirthDate()).isNull();
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    private void save(MlbPerson person) {
        writer.savePlayer(1L, person, null, NOW, List.of(), Map.of(), TODAY);
    }

    private static MlbPerson person(String birthDate, MlbTeam currentTeam) {
        return new MlbPerson(808982L, "Jung Hoo Lee", "51", birthDate, 28, "Seoul", null, "Republic of Korea",
                "6' 0\"", 196, true, new MlbPerson.Position("9", "Outfielder", "Outfielder", "RF"),
                new MlbPerson.Hand("L", "Left"), new MlbPerson.Hand("R", "Right"), "2024-03-28", currentTeam);
    }
}
