package com.korleaguer.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** resources/roster/baseball.yml 의 선수 명단 */
@ConfigurationProperties("roster")
public record RosterProperties(List<Entry> baseball) {

    /**
     * 한글 이름과 MLB 공식 선수 번호(mlbId). 선수의 모든 데이터는 mlbId로 MLB에서 받는다.
     * mlbId가 없으면 이름만 보이는 선수가 된다. careerKo(한국 시절 소속)는 사람이 적은 문장으로 그대로 화면에 쓴다(없으면 null).
     * returnedKo는 국내 리그로 돌아간 팀(예: "LG 트윈스")이다. 있으면 MLB 기록상 FA여도 상태가 'KBO 복귀'다(MLB 데이터에는
     * KBO 계약이 없어 사람이 적는다). 이제 해외파가 아니므로 다음 시즌이 시작되면 명단에서 뺀다.
     * kboId는 KBO 선수 번호(과거 소속팀 사진 주소에 쓴다), careerPhotos는 과거 소속팀 시절 사진(없으면 빈 목록)이다.
     */
    public record Entry(String nameKo, Long mlbId, String careerKo, String returnedKo, Long kboId,
                        List<CareerPhoto> careerPhotos) {

        public Entry {
            careerPhotos = careerPhotos == null ? List.of() : List.copyOf(careerPhotos);
        }
    }

    /**
     * 과거 소속팀(KBO) 시절 공식 사진 한 장. KBO 사진 서버의 그 시즌 선수 등록 사진을 쓴다(kbo-id와 season으로 주소를 만든다).
     *
     * @param team   화면에 쓰는 팀 이름(예: "키움 히어로즈")
     * @param years  그 팀에서 뛴 해(예: "2017–2023")
     * @param season 사진 시즌(보통 그 팀 마지막 해)
     */
    public record CareerPhoto(String team, String years, Integer season) {
    }
}
