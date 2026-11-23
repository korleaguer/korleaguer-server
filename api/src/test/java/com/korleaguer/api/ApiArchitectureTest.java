package com.korleaguer.api;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * api 모듈 안쪽 구조 규칙(ARCHITECTURE.md §3 '조회 안쪽'). 모듈 사이 경계는 Gradle이 막고, 모듈 안쪽은 이 테스트가 막는다.
 * <p>
 * 순수 계산 클래스: api의 클래스 중 스프링 빈(@Component·@Service·컨트롤러·설정)도 아니고 화면 응답 타입(이름이 Response로
 * 끝남)도 아닌 것. 그 안에 든 중첩 클래스는 바깥 클래스를 따른다. 값을 받아 값을 돌려주므로 스프링 없이 읽고 시험할 수 있어야 한다.
 * <p>
 * 주제 패키지(ARCHITECTURE.md §3 '조회 안쪽'): 위에서부터 web(컨트롤러·예외 처리), screen(화면 조립),
 * card·offseason(같은 층, 서로 모른다), records(역대 기록·화제 문구), season(시즌 성적), article(기사·포스팅 요약), game(경기),
 * player(선수 기본).
 * 아래 층은 위 층을 모른다. com.korleaguer.api 바로 아래(공통 예외)는 누구나 쓴다.
 * <p>
 * 규칙을 넣을 때(2026-11-23) 이미 어기던 곳은 얼려 두었다가 모두 고쳤다(#253·#255·#257). 이제 어기면 바로 실패한다.
 */
@AnalyzeClasses(packages = "com.korleaguer", importOptions = ImportOption.DoNotIncludeTests.class)
class ApiArchitectureTest {

    private static final String API = "com.korleaguer.api";

    private static final DescribedPredicate<JavaClass> PURE_CALCULATION = new DescribedPredicate<>(
            "are pure calculation classes of api (not Spring beans, not responses)") {
        @Override
        public boolean test(JavaClass javaClass) {
            JavaClass top = topLevel(javaClass);
            return inApi(top)
                    && !top.isMetaAnnotatedWith(Component.class)
                    && !top.getSimpleName().endsWith("Response");
        }
    };

    private static final DescribedPredicate<JavaClass> BEANS_OR_REPOSITORIES = new DescribedPredicate<>(
            "are Spring beans or repositories") {
        @Override
        public boolean test(JavaClass javaClass) {
            JavaClass top = topLevel(javaClass);
            return top.isMetaAnnotatedWith(Component.class) || top.isAssignableTo(Repository.class);
        }
    };

    private static final DescribedPredicate<JavaClass> RESPONSES = new DescribedPredicate<>(
            "are screen response types (…Response)") {
        @Override
        public boolean test(JavaClass javaClass) {
            JavaClass top = topLevel(javaClass);
            return inApi(top) && top.getSimpleName().endsWith("Response");
        }
    };

    /** 순수 계산 클래스는 스프링 빈·리포지토리를 모른다. 필요한 값은 부르는 쪽(빈)이 읽어 넘긴다 */
    @ArchTest
    static final ArchRule pureCalculationDoesNotKnowBeansOrRepositories = noClasses()
            .that(PURE_CALCULATION).should().dependOnClassesThat(BEANS_OR_REPOSITORIES)
            .as("순수 계산 클래스는 스프링 빈·리포지토리를 모른다");

    /** 순수 계산 클래스는 화면 응답 타입을 모른다. 방향은 응답 → 계산 타입이다(응답이 계산 결과를 그대로 품는다) */
    @ArchTest
    static final ArchRule pureCalculationDoesNotKnowResponses = noClasses()
            .that(PURE_CALCULATION).should().dependOnClassesThat(RESPONSES)
            .as("순수 계산 클래스는 화면 응답 타입(…Response)을 모른다");

    /** 주제는 아래 방향으로만 안다. 같은 층(card·offseason)끼리도 모른다 */
    @ArchTest
    static final ArchRule topicsKnowOnlyLowerTopics = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("web").definedBy(API + ".web..")
            .layer("screen").definedBy(API + ".screen..")
            .layer("card").definedBy(API + ".card..")
            .layer("records").definedBy(API + ".records..")
            .layer("offseason").definedBy(API + ".offseason..")
            .layer("season").definedBy(API + ".season..")
            .layer("article").definedBy(API + ".article..")
            .layer("game").definedBy(API + ".game..")
            .layer("player").definedBy(API + ".player..")
            .whereLayer("web").mayNotBeAccessedByAnyLayer()
            .whereLayer("screen").mayOnlyBeAccessedByLayers("web")
            .whereLayer("card").mayOnlyBeAccessedByLayers("web", "screen")
            .whereLayer("offseason").mayOnlyBeAccessedByLayers("web", "screen")
            .whereLayer("records").mayOnlyBeAccessedByLayers("web", "screen", "card", "offseason")
            .whereLayer("season").mayOnlyBeAccessedByLayers("web", "screen", "card", "offseason", "records")
            .whereLayer("article").mayOnlyBeAccessedByLayers("web", "screen", "card", "records", "offseason", "season")
            .whereLayer("game").mayOnlyBeAccessedByLayers("web", "screen", "card", "records", "offseason", "season",
                    "article")
            .whereLayer("player").mayOnlyBeAccessedByLayers("web", "screen", "card", "records", "offseason", "season",
                    "article", "game")
            .as("api 주제는 아래 방향으로만 안다(web > screen > card·offseason > records > season > article > game > player)");

    private static boolean inApi(JavaClass javaClass) {
        String name = javaClass.getPackageName();
        return name.equals(API) || name.startsWith(API + ".");
    }

    private static JavaClass topLevel(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }
}
