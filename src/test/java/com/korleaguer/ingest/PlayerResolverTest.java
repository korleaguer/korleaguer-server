package com.korleaguer.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.korleaguer.client.BdlPlayer;
import com.korleaguer.client.SampleBdlClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static com.korleaguer.client.BdlFixtures.player;
import static com.korleaguer.client.BdlFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerResolverTest {

    private final BdlPlayer haSeong = player(2103, "Ha-Seong Kim", "Kim", "Bucheon, South Korea", team(2, "ATL"));
    private final BdlPlayer hyeseong = player(2102, "Hyeseong Kim", "Kim", "Goyang, South Korea", team(14, "LAD"));
    private final BdlPlayer park = player(3001, "Goyang Park", "Park", "Seoul, South Korea", team(2, "ATL"));
    private final BdlPlayer jungHoo = player(2101, "Jung Hoo Lee", "Lee", "Nagoya, Japan", team(24, "SF"));
    private final RosterProperties.Entry jungHooEntry = new RosterProperties.Entry("이정후",
            List.of("Jung Hoo Lee", "Jung-Hoo Lee", "Lee"), null, null, null);

    // PlayerResolver의 로그를 모은다.
    private final Logger resolverLog = (Logger) LoggerFactory.getLogger(PlayerResolver.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void startCapturingLogs() {
        logs.start();
        resolverLog.addAppender(logs);
    }

    @AfterEach
    void stopCapturingLogs() {
        resolverLog.detachAppender(logs);
    }

    @Test
    void fixedIdIsUsedWithoutSearching() {
        SampleBdlClient client = new SampleBdlClient(List.of(haSeong, hyeseong), List.of(), List.of());
        RosterProperties.Entry entry = new RosterProperties.Entry("김하성", List.of("Kim"), 2103L, null, null);

        assertThat(new PlayerResolver(client).resolve(entry, Set.of())).contains(haSeong);
        assertThat(client.requestCount()).isEqualTo(1); // getPlayer 한 번, 검색은 안 함
    }

    @Test
    void exactNameMatchWins() {
        SampleBdlClient client = new SampleBdlClient(List.of(haSeong, hyeseong), List.of(), List.of());
        RosterProperties.Entry entry = new RosterProperties.Entry("김혜성", List.of("Hyeseong Kim", "Kim"), null, null, null);

        assertThat(new PlayerResolver(client).resolve(entry, Set.of())).contains(hyeseong);
    }

    @Test
    void idAlreadyUsedInThisRunIsNotPickedAgain() {
        SampleBdlClient client = new SampleBdlClient(List.of(haSeong, hyeseong), List.of(), List.of());
        RosterProperties.Entry surnameOnly = new RosterProperties.Entry("김하성", List.of("Kim"), null, null, null);
        PlayerResolver resolver = new PlayerResolver(client);

        // "Kim"으로는 두 명이 나와서 고르지 못한다.
        assertThat(resolver.resolve(surnameOnly, Set.of())).isEmpty();
        // 2102를 이미 다른 선수에게 썼으면 남은 한 명(성도 같다)을 고른다.
        assertThat(resolver.resolve(surnameOnly, Set.of(2102L))).contains(haSeong);
    }

    @Test
    void onlyCandidateWithDifferentSurnameIsRejected() {
        SampleBdlClient client = new SampleBdlClient(List.of(park), List.of(), List.of());
        RosterProperties.Entry entry = new RosterProperties.Entry("고우석", List.of("Woo-Suk Go", "Go"), null, null, null);

        // "Go" 검색에 "Goyang Park"이 걸리지만 성이 달라서 고르지 않는다.
        assertThat(new PlayerResolver(client).resolve(entry, Set.of())).isEmpty();
    }

    @Test
    void exactNameMatchIsPickedWhenNoKoreanBornCandidateAndWarns() {
        SampleBdlClient client = new SampleBdlClient(List.of(jungHoo), List.of(), List.of());

        assertThat(new PlayerResolver(client).resolve(jungHooEntry, Set.of())).contains(jungHoo);
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("이정후").contains("Nagoya, Japan");
        });
    }

    @Test
    void exactNameMatchIsNotPickedWhenTwoPlayersShareTheName() {
        BdlPlayer namesake = player(9001, "Jung Hoo Lee", "Lee", "Dallas, USA", team(2, "ATL"));
        SampleBdlClient client = new SampleBdlClient(List.of(jungHoo, namesake), List.of(), List.of());

        assertThat(new PlayerResolver(client).resolve(jungHooEntry, Set.of())).isEmpty();
    }

    @Test
    void exactNameMatchSkipsIdAlreadyUsedInThisRun() {
        BdlPlayer namesake = player(9001, "Jung Hoo Lee", "Lee", "Dallas, USA", team(2, "ATL"));
        PlayerResolver both = new PlayerResolver(new SampleBdlClient(List.of(jungHoo, namesake), List.of(), List.of()));
        PlayerResolver alone = new PlayerResolver(new SampleBdlClient(List.of(jungHoo), List.of(), List.of()));

        // 2101을 이미 다른 선수에게 썼으면 남은 동명이인 한 명을 고른다.
        assertThat(both.resolve(jungHooEntry, Set.of(2101L))).contains(namesake);
        // 하나뿐인 후보를 이미 썼으면 고를 선수가 없다.
        assertThat(alone.resolve(jungHooEntry, Set.of(2101L))).isEmpty();
    }

    @Test
    void foreignBornPlayerWithDifferentNameIsNotPicked() {
        BdlPlayer bruceLee = player(9002, "Bruce Lee", "Lee", "Seattle, USA", team(2, "ATL"));
        SampleBdlClient client = new SampleBdlClient(List.of(bruceLee), List.of(), List.of());

        assertThat(new PlayerResolver(client).resolve(jungHooEntry, Set.of())).isEmpty();
    }

    @Test
    void foreignBornExactNameBeatsTheOnlyKoreanBornSameSurname() {
        // "Lee" 검색에 한국 출생 다른 Lee가 한 명 걸린다. 성이 같다는 이유로 그 선수를 고르면 안 된다.
        BdlPlayer daeHo = player(3101, "Dae-Ho Lee", "Lee", "Seoul, South Korea", team(2, "ATL"));
        SampleBdlClient client = new SampleBdlClient(List.of(daeHo, jungHoo), List.of(), List.of());

        assertThat(new PlayerResolver(client).resolve(jungHooEntry, Set.of())).contains(jungHoo);
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("이정후").contains("Nagoya, Japan");
        });
    }

    @Test
    void foreignBornExactNameBeatsSeveralKoreanBornCandidates() {
        BdlPlayer lee1 = player(3101, "Sang Lee", "Lee", "Seoul, South Korea", team(2, "ATL"));
        BdlPlayer lee2 = player(3102, "Min Lee", "Lee", "Busan, South Korea", team(2, "ATL"));
        SampleBdlClient client = new SampleBdlClient(List.of(jungHoo, lee1, lee2), List.of(), List.of());

        assertThat(new PlayerResolver(client).resolve(jungHooEntry, Set.of())).contains(jungHoo);
    }

    @Test
    void koreanBornExactNameWinsOverForeignNamesake() {
        BdlPlayer koreanJungHoo = player(9003, "Jung Hoo Lee", "Lee", "Seoul, South Korea", team(2, "ATL"));
        SampleBdlClient client = new SampleBdlClient(List.of(jungHoo, koreanJungHoo), List.of(), List.of());

        // 한국 출생 후보 중 이름이 같은 선수가 한 명이면 먼저 고르고, WARN은 없다.
        assertThat(new PlayerResolver(client).resolve(jungHooEntry, Set.of())).contains(koreanJungHoo);
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void ambiguousExactNameFallsBackToTheOnlyKoreanBornSameSurname() {
        // 이름이 같은 외국 출생이 둘이라 3단계에서 못 고른다. 4단계(성이 같은 한국 출생 단독 후보)로 넘어간다.
        BdlPlayer namesake = player(9001, "Jung Hoo Lee", "Lee", "Dallas, USA", team(2, "ATL"));
        BdlPlayer daeHo = player(3101, "Dae-Ho Lee", "Lee", "Seoul, South Korea", team(2, "ATL"));
        SampleBdlClient client = new SampleBdlClient(List.of(jungHoo, namesake, daeHo), List.of(), List.of());

        assertThat(new PlayerResolver(client).resolve(jungHooEntry, Set.of())).contains(daeHo);
    }
}
