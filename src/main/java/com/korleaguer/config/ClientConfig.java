package com.korleaguer.config;

import com.korleaguer.client.BdlClient;
import com.korleaguer.client.HttpBdlClient;
import com.korleaguer.client.SampleBdlClient;
import com.korleaguer.support.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/** 데이터 모드에 따라 실제 클라이언트나 샘플 클라이언트 중 하나를 BdlClient 빈으로 등록한다. */
@Configuration
public class ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientConfig.class);

    @Bean
    public DataMode dataMode(KorleaguerProperties properties) {
        DataMode mode = DataMode.resolve(properties.data().mode(), properties.bdl().apiKey());
        log.info("data mode: {}", mode.value());
        return mode;
    }

    @Bean
    public BdlClient bdlClient(DataMode dataMode, KorleaguerProperties properties, JsonMapper jsonMapper) {
        if (dataMode == DataMode.SAMPLE) {
            return new SampleBdlClient(jsonMapper);
        }
        KorleaguerProperties.Bdl bdl = properties.bdl();
        // 연결 5초, 응답 읽기 30초를 넘기면 실패로 보고 HttpBdlClient가 다시 시도한다.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(bdl.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, bdl.apiKey())
                .build();
        return new HttpBdlClient(restClient, new RateLimiter(bdl.requestsPerMinute()));
    }
}
