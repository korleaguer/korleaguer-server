package com.korleaguer.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.korleaguer.client.BdlPlayer;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 선수를 매칭할 때 balldontlie 선수 필드(생년월일·키·몸무게·투타·등번호·데뷔 연도·나이)를 저장한다. */
class IngestWriterProfileTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private final PlayerRepository players = mock(PlayerRepository.class);
    private final IngestWriter writer = new IngestWriter(mock(TeamRepository.class), players,
            mock(AffiliationRepository.class), mock(GameRepository.class), mock(AppearanceRepository.class));
    private final Player player = new Player("이정후");

    private final Logger writerLog = (Logger) LoggerFactory.getLogger(IngestWriter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(players.findByNameKo("이정후")).thenReturn(Optional.of(player));
        logs.start();
        writerLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        writerLog.detachAppender(logs);
    }

    @Test
    void profileFieldsAreSaved() {
        writer.savePlayer("이정후", source("1998-08-20"), TODAY);

        assertThat(player.getBirthDate()).isEqualTo(LocalDate.of(1998, 8, 20));
        assertThat(player.getHeight()).isEqualTo("6' 2\"");
        assertThat(player.getWeight()).isEqualTo("190 lbs");
        assertThat(player.getBatsThrows()).isEqualTo("Left/Right");
        assertThat(player.getJersey()).isEqualTo("51");
        assertThat(player.getDebutYear()).isEqualTo(2024);
        assertThat(player.getAge()).isEqualTo(27);
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void usStyleBirthDateIsSaved() {
        writer.savePlayer("이정후", source("8/6/1998"), TODAY);

        assertThat(player.getBirthDate()).isEqualTo(LocalDate.of(1998, 8, 6));
    }

    @Test
    void unreadableBirthDateIsLeftEmptyWithOneWarning() {
        writer.savePlayer("이정후", source("sometime in 1998"), TODAY);

        assertThat(player.getBirthDate()).isNull();
        assertThat(player.getHeight()).isEqualTo("6' 2\"");
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("이정후").contains("sometime in 1998"));
    }

    @Test
    void missingBirthDateIsLeftEmptyWithoutWarning() {
        writer.savePlayer("이정후", source(null), TODAY);

        assertThat(player.getBirthDate()).isNull();
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    private static BdlPlayer source(String dob) {
        return new BdlPlayer(2101L, "Jung Hoo", "Lee", "Jung Hoo Lee", "Center Fielder", true, "Nagoya, Japan",
                dob, "Left/Right", 2024, "51", 27, "6' 2\"", "190 lbs", null);
    }
}
