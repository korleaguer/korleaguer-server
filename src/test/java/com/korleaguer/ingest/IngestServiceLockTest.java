package com.korleaguer.ingest;

import com.korleaguer.client.BdlPlayer;
import com.korleaguer.client.SampleBdlClient;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.IngestStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 수집은 한 번에 하나만 돈다. */
class IngestServiceLockTest {

    @Test
    void secondIngestIsRejectedWhileFirstIsRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 검색 중에 멈춰 서는 가짜 클라이언트
        SampleBdlClient blockingClient = new SampleBdlClient(List.of(), List.of(), List.of()) {
            @Override
            public List<BdlPlayer> searchPlayers(String search) {
                entered.countDown();
                await(release);
                return List.of();
            }
        };
        IngestLogRepository ingestLogs = mock(IngestLogRepository.class);
        when(ingestLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AffiliationRepository affiliations = mock(AffiliationRepository.class);
        when(affiliations.findAllCurrent()).thenReturn(List.of());
        RosterProperties roster = new RosterProperties(
                List.of(new RosterProperties.Entry("김하성", List.of("Ha-Seong Kim"), null, null, null)));
        MovesRefresher moves = mock(MovesRefresher.class);
        when(moves.refreshStale()).thenReturn(new MovesRefresher.Result(0, 0, false, 0));
        IngestService service = new IngestService(blockingClient, new PlayerResolver(blockingClient),
                mock(IngestWriter.class), moves, affiliations, ingestLogs, roster, Clock.systemUTC());

        Thread first = new Thread(service::resolvePlayers);
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.ingestSeason(2026)).isInstanceOf(IngestAlreadyRunningException.class);

        release.countDown();
        first.join(5_000);
        // 앞의 수집이 끝나면 다시 돌 수 있다(선수가 없어서 FAILED로 끝난다).
        IngestLog next = service.ingestSeason(2026);
        assertThat(next.getStatus()).isEqualTo(IngestStatus.FAILED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
