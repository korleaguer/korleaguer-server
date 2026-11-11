package com.korleaguer.api;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * GET /actuator/warm: 화면이 부를 응답을 미리 채웠나(CacheWarmer.ready). 채웠으면 200, 아직이면 503.
 * 배포 스크립트(deploy/korleaguer-deploy)가 새 API로 요청을 넘기기 전에 이것을 기다린다. 막 뜬 서버가 빈 캐시로
 * 요청을 받아 운영 장비에서 1~2초씩 걸리지 않게 하려는 것이다.
 * 컨테이너 상태 검사(HEALTHCHECK, /actuator/health)와는 따로다: 채우는 동안에도 서버는 정상(healthy)이다.
 */
@Component
@Endpoint(id = "warm")
public class WarmEndpoint {

    private final CacheWarmer warmer;

    public WarmEndpoint(CacheWarmer warmer) {
        this.warmer = warmer;
    }

    @ReadOperation
    public WebEndpointResponse<Map<String, Boolean>> warm() {
        boolean ready = warmer.ready();
        return new WebEndpointResponse<>(Map.of("warm", ready), ready ? WebEndpointResponse.STATUS_OK
                : WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE);
    }
}
