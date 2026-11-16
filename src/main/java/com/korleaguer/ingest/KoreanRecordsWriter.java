package com.korleaguer.ingest;

import com.korleaguer.domain.KoreanMlbSeason;
import com.korleaguer.domain.KoreanMlbSeasonRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 한국인 메이저리거 시즌 기록(korean_mlb_season)을 한 선수씩 한 트랜잭션으로 덮어쓴다(이슈 #177) */
@Component
@Transactional
public class KoreanRecordsWriter {

    private final KoreanMlbSeasonRepository seasons;

    public KoreanRecordsWriter(KoreanMlbSeasonRepository seasons) {
        this.seasons = seasons;
    }

    /** 받은 시즌마다 줄을 덮어쓴다. 받은 기록에 없는 옛 줄은 그대로 둔다(MLB가 지난 시즌을 지우지는 않는다) */
    public void save(long mlbId, PlayerSeasonMetric.Group group, Map<Integer, KoreanMlbSeason.Counts> counts,
                     Instant fetchedAt) {
        Map<Integer, KoreanMlbSeason> saved = seasons.findByMlbIdAndStatGroup(mlbId, group).stream()
                .collect(Collectors.toMap(KoreanMlbSeason::getSeason, Function.identity(), (a, b) -> a));
        counts.forEach((season, count) -> {
            KoreanMlbSeason row = saved.get(season);
            if (row == null) {
                row = new KoreanMlbSeason(mlbId, season, group);
            }
            row.update(count, fetchedAt);
            seasons.save(row);
        });
    }
}
