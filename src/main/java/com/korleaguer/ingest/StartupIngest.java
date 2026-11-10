package com.korleaguer.ingest;

import com.korleaguer.config.DataMode;
import com.korleaguer.config.KorleaguerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * 앱이 뜰 때 따라잡기(선수 정보, 아직 안 받은 시즌)를 한 번 돈다.
 * - sample: 바로 끝까지 돈다(네트워크 없음, 테스트가 이 데이터를 쓴다).
 * - mlb: 자동 수집이 켜져 있을 때만, 앱이 요청을 받는 것을 막지 않도록 뒤에서 돈다.
 */
@Component
public class StartupIngest implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIngest.class);

    private final DataMode dataMode;
    private final IngestService ingestService;
    private final KorleaguerProperties properties;
    private final ExecutorService executor;

    public StartupIngest(DataMode dataMode, IngestService ingestService, KorleaguerProperties properties,
                         @Qualifier("ingestExecutor") ExecutorService executor) {
        this.dataMode = dataMode;
        this.ingestService = ingestService;
        this.properties = properties;
        this.executor = executor;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (dataMode == DataMode.SAMPLE) {
            ingestService.catchUp(false);
            return;
        }
        if (!properties.ingest().scheduleEnabled()) {
            return;
        }
        executor.execute(() -> {
            try {
                ingestService.catchUp(false);
            } catch (IngestAlreadyRunningException e) {
                log.info("skip startup ingest: another ingest is running");
            }
        });
    }
}
