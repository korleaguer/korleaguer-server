package com.korleaguer.config;

import com.korleaguer.client.HttpMlbClient;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.support.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/** 데이터 출처(korleaguer.data-source)에 따라 실제 MLB 클라이언트나 샘플 클라이언트를 MlbClient 빈으로 등록한다. */
@Configuration
public class ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientConfig.class);

    @Bean
    public DataMode dataMode(KorleaguerProperties properties) {
        DataMode mode = DataMode.resolve(properties.dataSource());
        log.info("data source: {}", mode.value());
        return mode;
    }

    /** sample이면 샘플(네트워크 없음), 아니면 MLB 공식 Stats API */
    @Bean
    public MlbClient mlbClient(DataMode dataMode, KorleaguerProperties properties, JsonMapper jsonMapper) {
        if (dataMode == DataMode.SAMPLE) {
            return new SampleMlbClient(jsonMapper);
        }
        KorleaguerProperties.Mlb mlb = properties.mlb();
        // 연결 5초, 응답 읽기 10초를 넘기면 그 호출만 실패로 본다(다시 시도하지 않는다).
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(mlb.baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, mlb.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        // 요청 사이를 1초 이상 띄운다.
        return new HttpMlbClient(restClient, new RateLimiter(1, Duration.ofSeconds(1)));
    }
}
