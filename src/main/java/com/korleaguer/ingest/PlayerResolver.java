package com.korleaguer.ingest;

import com.korleaguer.client.BdlClient;
import com.korleaguer.client.BdlPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 명단의 한 선수를 balldontlie 선수 한 명과 짝짓는다.
 * 0) yml에 bdl-player-id가 있으면 검색하지 않고 그 선수를 쓴다.
 * 1) 별칭으로 차례로 검색하고 birth_place에 "Korea"가 들어간 선수만 후보로 남긴다.
 *    이번 실행에서 이미 다른 선수에게 쓴 id는 후보에서 뺀다.
 * 2) 후보 중 이름이 별칭과 일치하는 선수가 딱 한 명이면 고른다(남은 검색어는 쓰지 않는다).
 * 3) 검색어를 다 쓰고도 못 골랐으면, 출생지와 상관없이 검색 결과 전체에서 이름이 별칭과 정확히 일치하는 선수가
 *    딱 한 명일 때 그 선수를 고른다(이미 다른 선수에게 쓴 id는 뺀다). 한국 출생이 아니면(예: 이정후는 일본
 *    나고야 출생) WARN을 남긴다. 둘 이상이면 고르지 않는다.
 *    4단계보다 앞이라서, "Lee" 검색에 한국 출생 다른 Lee가 한 명 걸려도 성이 같다는 이유로 이정후 자리에
 *    들어가지 않는다. 검색어를 다 쓴 뒤에 판단하므로 뒤 검색어에서 나온 한국 출생 후보는 2단계에서 먼저 반영된다.
 * 4) 그래도 못 골랐는데 한국 출생 후보가 한 명뿐이고 성까지 같으면 그 선수를 고르되 WARN을 남긴다.
 * 5) 그 밖에는 고르지 않고 로그를 남긴다. 사람이 yml에 bdl-player-id를 적어 정한다.
 */
@Component
public class PlayerResolver {

    private static final Logger log = LoggerFactory.getLogger(PlayerResolver.class);

    private final BdlClient client;

    public PlayerResolver(BdlClient client) {
        this.client = client;
    }

    public Optional<BdlPlayer> resolve(RosterProperties.Entry entry, Set<Long> usedIds) {
        if (entry.bdlPlayerId() != null) {
            BdlPlayer fixed = client.getPlayer(entry.bdlPlayerId());
            if (fixed == null) {
                log.warn("[resolve] {}: bdl-player-id {} not found", entry.nameKo(), entry.bdlPlayerId());
            }
            return Optional.ofNullable(fixed);
        }

        Map<Long, BdlPlayer> koreans = new LinkedHashMap<>();
        // 출생지와 상관없는 검색 결과 전체(3단계에서만 쓴다)
        Map<Long, BdlPlayer> found = new LinkedHashMap<>();
        for (String alias : entry.aliases()) {
            for (BdlPlayer candidate : client.searchPlayers(alias)) {
                if (usedIds.contains(candidate.id())) {
                    continue;
                }
                found.putIfAbsent(candidate.id(), candidate);
                if (isKorean(candidate)) {
                    koreans.putIfAbsent(candidate.id(), candidate);
                }
            }
            Optional<BdlPlayer> nameMatch = pickByName(koreans.values(), entry.aliases());
            if (nameMatch.isPresent()) {
                return nameMatch;
            }
        }

        Optional<BdlPlayer> exactName = pickByName(found.values(), entry.aliases());
        if (exactName.isPresent()) {
            BdlPlayer picked = exactName.get();
            if (!isKorean(picked)) {
                log.warn("[resolve] {}: picked '{}' (id {}, born in '{}') because the name matches exactly, "
                                + "though not Korean-born. Set bdl-player-id in baseball.yml to confirm",
                        entry.nameKo(), picked.fullName(), picked.id(), picked.birthPlace());
            }
            return exactName;
        }

        if (koreans.size() == 1) {
            BdlPlayer only = koreans.values().iterator().next();
            if (normalize(only.lastName()).equals(normalize(entry.surname()))) {
                log.warn("[resolve] {}: no exact name match, picked the only Korean-born '{}' (id {}) by surname. "
                        + "Set bdl-player-id in baseball.yml to confirm", entry.nameKo(), only.fullName(), only.id());
                return Optional.of(only);
            }
        }
        if (koreans.isEmpty()) {
            log.warn("[resolve] {}: no Korean-born candidate found for aliases {}", entry.nameKo(), entry.aliases());
        } else {
            log.warn("[resolve] {}: not sure, pick one by hand (bdl-player-id in baseball.yml): {}", entry.nameKo(),
                    koreans.values().stream().map(p -> p.id() + " " + p.fullName()).toList());
        }
        return Optional.empty();
    }

    private static boolean isKorean(BdlPlayer player) {
        return player.birthPlace() != null && player.birthPlace().contains("Korea");
    }

    private static Optional<BdlPlayer> pickByName(Collection<BdlPlayer> candidates, List<String> aliases) {
        Set<String> names = aliases.stream().map(PlayerResolver::normalize).collect(Collectors.toSet());
        List<BdlPlayer> matched = candidates.stream()
                .filter(p -> names.contains(normalize(p.fullName())))
                .toList();
        return matched.size() == 1 ? Optional.of(matched.getFirst()) : Optional.empty();
    }

    // "Jung-Hoo Lee"와 "Jung Hoo Lee"를 같은 이름으로 본다.
    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
