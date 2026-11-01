package com.korleaguer.ingest;

import com.korleaguer.config.DataMode;
import com.korleaguer.config.KorleaguerProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 샘플 모드면 앱이 뜰 때 샘플 데이터를 자동으로 수집한다. */
@Component
public class SampleDataLoader implements ApplicationRunner {

    private final DataMode dataMode;
    private final IngestService ingestService;
    private final KorleaguerProperties properties;

    public SampleDataLoader(DataMode dataMode, IngestService ingestService, KorleaguerProperties properties) {
        this.dataMode = dataMode;
        this.ingestService = ingestService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (dataMode != DataMode.SAMPLE) {
            return;
        }
        ingestService.resolvePlayers();
        ingestService.ingestSeason(properties.season());
    }
}
