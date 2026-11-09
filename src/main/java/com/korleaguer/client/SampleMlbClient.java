package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 샘플 모드에서 쓰는 MLB 클라이언트. 네트워크를 쓰지 않는다.
 * resources/sample/mlb.json(MLB 응답과 같은 모양, 지어낸 값이며 실제 기록이 아니다)을 읽어 실제 API처럼 걸러서 돌려준다.
 * MLB 30개 구단은 팀 조회를 하지 않으므로 teams에는 마이너 팀만 둔다.
 */
public class SampleMlbClient implements MlbClient {

    private static final String SAMPLE_PATH = "sample/mlb.json";

    private final List<MlbPerson> people;
    private final List<MlbTeam> teams;
    private final List<MlbTransaction> transactions;
    private final AtomicLong requestCount = new AtomicLong();

    public SampleMlbClient(JsonMapper jsonMapper) {
        this(read(jsonMapper));
    }

    /** 데이터를 직접 넣는 생성자(테스트에서 상황을 만들 때 쓴다) */
    public SampleMlbClient(List<MlbPerson> people, List<MlbTeam> teams, List<MlbTransaction> transactions) {
        this.people = people;
        this.teams = teams;
        this.transactions = transactions;
    }

    private SampleMlbClient(SampleFile file) {
        this(orEmpty(file.people()), orEmpty(file.teams()), orEmpty(file.transactions()));
    }

    @Override
    public MlbPerson getPerson(long personId) {
        requestCount.incrementAndGet();
        return people.stream().filter(p -> Objects.equals(p.id(), personId)).findFirst().orElse(null);
    }

    @Override
    public List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate) {
        requestCount.incrementAndGet();
        return transactions.stream()
                .filter(t -> t.person() != null && Objects.equals(t.person().id(), personId))
                .filter(t -> within(t.date(), startDate, endDate))
                .toList();
    }

    @Override
    public MlbTeam getTeam(long teamId) {
        requestCount.incrementAndGet();
        return teams.stream().filter(t -> Objects.equals(t.id(), teamId)).findFirst().orElse(null);
    }

    @Override
    public long requestCount() {
        return requestCount.get();
    }

    private static boolean within(String date, LocalDate start, LocalDate end) {
        try {
            LocalDate day = LocalDate.parse(date);
            return !day.isBefore(start) && !day.isAfter(end);
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static SampleFile read(JsonMapper jsonMapper) {
        try (InputStream in = new ClassPathResource(SAMPLE_PATH).getInputStream()) {
            return jsonMapper.readValue(in, SampleFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SAMPLE_PATH, e);
        }
    }

    /** sample/mlb.json의 모양. 각 목록은 MLB 응답의 people·teams·transactions와 같은 모양이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SampleFile(List<MlbPerson> people, List<MlbTeam> teams, List<MlbTransaction> transactions) {
    }
}
