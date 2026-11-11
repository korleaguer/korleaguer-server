package com.korleaguer.ingest;

import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.config.DataMode;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.IngestStatus;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonRepository;
import com.korleaguer.domain.SeasonDatesRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 수집은 한 번에 하나만 돈다. */
class IngestServiceLockTest {

    @Test
    void secondIngestIsRejectedWhileFirstIsRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 선수 정보를 받는 중에 멈춰 서는 가짜 수집기
        PlayerRefresher blockingRefresher = mock(PlayerRefresher.class);
        when(blockingRefresher.refresh(anyBoolean())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new PlayerRefresher.Result(0, 0, false, 0);
        });
        IngestLogRepository ingestLogs = mock(IngestLogRepository.class);
        when(ingestLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlayerRepository players = mock(PlayerRepository.class);
        when(players.findByMlbIdIsNotNull()).thenReturn(List.of());
        RosterProperties roster = new RosterProperties(List.of(new RosterProperties.Entry("김하성", 673490L, null, null)));
        CurrentSeason currentSeason = new CurrentSeason(new KorleaguerProperties(2026, null, null, null, null, null),
                DataMode.MLB, mock(SeasonDatesRepository.class), Clock.systemUTC());
        IngestService service = new IngestService(new SampleMlbClient(SampleMlbClient.Data.empty()),
                mock(IngestWriter.class), blockingRefresher, mock(SeasonDatesRefresher.class), mock(GameIngest.class),
                players, mock(PlayerSeasonRepository.class), ingestLogs, roster, currentSeason, Clock.systemUTC());

        Thread first = new Thread(service::syncPlayers);
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.ingestSeason(2026)).isInstanceOf(IngestAlreadyRunningException.class);

        release.countDown();
        first.join(5_000);
        // 앞의 수집이 끝나면 다시 돌 수 있다.
        IngestLog next = service.ingestSeason(2026);
        assertThat(next.getStatus()).isEqualTo(IngestStatus.SUCCESS);
    }
}
