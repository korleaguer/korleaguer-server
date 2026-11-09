package com.korleaguer.api;

import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Team;
import com.korleaguer.support.KoreanTime;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 비시즌에 대신 보여 줄 "마지막 경기일"(한국 날짜). 경기 한 줄이 생기는 가장 늦은 날이다.
 * <ul>
 *   <li>메이저에 있는 선수는 구단의 끝난 경기마다 줄이 생긴다(뛰었거나 결장).</li>
 *   <li>마이너에 있는 선수는 소속 구단 경기가 아니라 본인이 뛴 경기에만 줄이 생긴다
 *       (RosterMember.majorTeam). 그래서 구단의 늦은 경기(포스트시즌 등)로 날짜를 잡지 않는다.</li>
 * </ul>
 * 홈(RecentGamesFinder)과 내 선수(MyPlayersService)가 같은 규칙을 쓴다.
 */
@Component
class LastGameDay {

    private final GameRepository games;
    private final AppearanceRepository appearances;

    LastGameDay(GameRepository games, AppearanceRepository appearances) {
        this.games = games;
        this.appearances = appearances;
    }

    /** 선수들의 마지막 경기일. 줄이 생길 경기가 하나도 없으면 빈 값 */
    Optional<LocalDate> find(List<RosterMember> members, Instant now) {
        List<Long> teamIds = members.stream()
                .map(RosterMember::majorTeam)
                .filter(Objects::nonNull)
                .map(Team::getMlbTeamId)
                .distinct()
                .toList();
        List<Long> playerIds = members.stream().map(m -> m.player().getId()).toList();
        Optional<Instant> teamLast = teamIds.isEmpty()
                ? Optional.empty() : games.findLastFinalStartTime(teamIds, now);
        Optional<Instant> ownLast = playerIds.isEmpty()
                ? Optional.empty() : appearances.findLastStartTime(playerIds, now);
        return Stream.of(teamLast, ownLast)
                .flatMap(Optional::stream)
                .max(Comparator.naturalOrder())
                .map(KoreanTime::kstDate);
    }
}
