package com.korleaguer.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 응답 모양 명세(저장소 맨 위 openapi.json). 웹은 이 파일에서 타입을 만든다.
 *
 * springdoc이 뽑은 날것을 웹이 쓸 모양으로 다듬고(tidy), 실제 응답 JSON이 명세와 맞는지 본다(mismatches).
 * - record의 필드는 Jackson이 null이어도 늘 쓰므로 모두 필수(required)다.
 * - null이 될 수 있는 필드는 record 필드 타입에 JSpecify {@code @Nullable}을 단다. 달지 않은 필드는 null이 아니라고 약속한다.
 * - 타자·투수로 갈리는 sealed interface는 구분 필드 없이 나가므로 "둘 중 하나"(oneOf)로 적는다.
 * - 중첩 record는 바깥 이름을 앞에 붙인다(SeasonReport.Month → SeasonReportMonth). Item·Line 같은 이름이 홀로 남지 않게 한다.
 */
final class OpenApiSpec {

    /** app 모듈 폴더에서 테스트가 돈다. 명세 파일은 저장소 맨 위에 둔다. */
    static final Path FILE = Path.of("..", "openapi.json");

    private static final String SCHEMA_REF = "#/components/schemas/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenApiSpec() {
    }

    /** springdoc이 전체 이름(use-fqn)으로 뽑은 명세를 다듬어 파일에 쓸 글로 돌려준다. */
    static String tidy(String raw) throws Exception {
        ObjectNode doc = (ObjectNode) JSON.readTree(raw);
        doc.remove("servers");
        ObjectNode info = JSON.createObjectNode();
        info.put("title", "KOR Leaguer API");
        info.put("version", "1");
        doc.set("info", info);

        ObjectNode rawSchemas = (ObjectNode) doc.path("components").path("schemas");
        Map<String, Class<?>> classes = new LinkedHashMap<>();
        rawSchemas.fieldNames().forEachRemaining(fqn -> classes.put(fqn, load(fqn)));
        Map<String, String> names = shortNames(classes);

        Map<String, JsonNode> schemas = new TreeMap<>();
        for (Map.Entry<String, Class<?>> e : classes.entrySet()) {
            schemas.put(names.get(e.getKey()), shape(e.getValue(), rawSchemas.get(e.getKey())));
        }
        ObjectNode components = JSON.createObjectNode();
        ObjectNode schemaNode = components.putObject("schemas");
        schemas.forEach(schemaNode::set);
        doc.set("components", components);

        ObjectNode paths = JSON.createObjectNode();
        new TreeMap<>(toMap(doc.path("paths"))).forEach((path, item) -> {
            ObjectNode operations = (ObjectNode) item;
            operations.forEach(op -> {
                if (op.has("responses")) {
                    ((ObjectNode) op).set("responses", sorted(op.get("responses")));
                }
            });
            paths.set(path, operations);
        });
        doc.set("paths", paths);

        renameRefs(doc, names);
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return JSON.writer(printer).writeValueAsString(doc) + "\n";
    }

    private static Class<?> load(String fqn) {
        // 중첩 클래스는 a.b.Outer.Inner로 온다. 뒤에서부터 '.'을 '$'로 바꿔 가며 찾는다.
        String name = fqn;
        while (true) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    throw new IllegalStateException("명세의 타입을 클래스로 찾지 못했다: " + fqn);
                }
                name = name.substring(0, dot) + "$" + name.substring(dot + 1);
            }
        }
    }

    /** 짧은 이름. 중첩 record는 바깥 클래스 이름을 앞에 붙인다(PostingSummary.Player → PostingSummaryPlayer). */
    private static Map<String, String> shortNames(Map<String, Class<?>> classes) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        classes.forEach((fqn, c) -> {
            String name = qualified(c);
            if (!used.add(name)) {
                throw new IllegalStateException("명세 타입 이름이 겹친다: " + name);
            }
            names.put(fqn, name);
        });
        return names;
    }

    private static String qualified(Class<?> c) {
        return c.getEnclosingClass() == null ? c.getSimpleName() : qualified(c.getEnclosingClass()) + c.getSimpleName();
    }

    private static JsonNode shape(Class<?> type, JsonNode schema) {
        if (type.isEnum()) {
            return schema;
        }
        if (type.isInterface() && type.isSealed()) {
            ObjectNode oneOf = JSON.createObjectNode();
            ArrayNode refs = oneOf.putArray("oneOf");
            for (Class<?> sub : type.getPermittedSubclasses()) {
                refs.addObject().put("$ref", SCHEMA_REF + sub.getCanonicalName());
            }
            return oneOf;
        }
        if (!type.isRecord()) {
            throw new IllegalStateException("응답에 record가 아닌 타입이 있다. 필드가 늘 나가는지 알 수 없다: " + type.getName());
        }
        JsonNode object = schema;
        if (schema.has("allOf")) {
            // sealed interface를 구현한 record는 부모 참조 + 자기 필드로 온다. 부모는 빈 껍데기라 자기 필드만 남긴다.
            object = null;
            for (JsonNode part : schema.get("allOf")) {
                if (part.has("properties")) {
                    object = part;
                }
            }
        }
        JsonNode properties = object == null ? JSON.createObjectNode() : object.path("properties");
        ObjectNode shaped = JSON.createObjectNode();
        shaped.put("type", "object");
        ObjectNode props = shaped.putObject("properties");
        ArrayNode required = shaped.putArray("required");
        Set<String> seen = new LinkedHashSet<>();
        for (RecordComponent rc : type.getRecordComponents()) {
            String name = jsonName(type, rc);
            JsonNode prop = properties.get(name);
            if (prop == null) {
                throw new IllegalStateException("명세에 필드가 없다: " + type.getName() + "." + name);
            }
            seen.add(name);
            AnnotatedType annotated = rc.getAnnotatedType();
            if (annotated.isAnnotationPresent(Nullable.class)) {
                prop = orNull(prop);
            } else if (nullableItems(annotated) && prop.has("items")) {
                ((ObjectNode) prop).set("items", orNull(prop.get("items")));
            }
            props.set(name, prop);
            required.add(name);
        }
        properties.fieldNames().forEachRemaining(name -> {
            if (!seen.contains(name)) {
                throw new IllegalStateException("record 필드가 아닌 값이 명세에 있다: " + type.getName() + "." + name);
            }
        });
        return shaped;
    }

    private static String jsonName(Class<?> type, RecordComponent rc) {
        JsonProperty renamed = rc.getAccessor().getAnnotation(JsonProperty.class);
        if (renamed == null) {
            try {
                renamed = type.getDeclaredField(rc.getName()).getAnnotation(JsonProperty.class);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
        return renamed == null || renamed.value().isEmpty() ? rc.getName() : renamed.value();
    }

    private static boolean nullableItems(AnnotatedType annotated) {
        return annotated instanceof AnnotatedParameterizedType p
                && p.getType() instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() instanceof Class<?> raw && Collection.class.isAssignableFrom(raw)
                && p.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class);
    }

    /** 값 또는 null. 참조면 anyOf로 감싸고, 기본 타입이면 type 목록에 "null"을 더한다. */
    private static JsonNode orNull(JsonNode prop) {
        ObjectNode copy = prop.deepCopy();
        JsonNode type = copy.get("type");
        if (type != null && type.isTextual()) {
            ArrayNode types = JSON.createArrayNode().add(type.asText()).add("null");
            copy.set("type", types);
            if (copy.has("enum")) {
                ((ArrayNode) copy.get("enum")).addNull();
            }
            return copy;
        }
        ObjectNode anyOf = JSON.createObjectNode();
        anyOf.putArray("anyOf").add(copy).addObject().put("type", "null");
        return anyOf;
    }

    private static ObjectNode sorted(JsonNode node) {
        ObjectNode out = JSON.createObjectNode();
        new TreeMap<>(toMap(node)).forEach(out::set);
        return out;
    }

    private static Map<String, JsonNode> toMap(JsonNode node) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        node.properties().forEach(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }

    private static void renameRefs(JsonNode node, Map<String, String> names) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("$ref");
            if (ref != null && ref.asText().startsWith(SCHEMA_REF)) {
                String fqn = ref.asText().substring(SCHEMA_REF.length());
                String name = names.get(fqn);
                if (name == null) {
                    throw new IllegalStateException("명세에 없는 타입을 가리킨다: " + fqn);
                }
                object.put("$ref", SCHEMA_REF + name);
            }
            object.forEach(child -> renameRefs(child, names));
        } else if (node instanceof ArrayNode array) {
            array.forEach(child -> renameRefs(child, names));
        }
    }

    /** 명세를 뽑는다(springdoc). 테스트 클래스는 springdoc.use-fqn=true로 띄운다. */
    static String fromApp(MockMvc mvc) throws Exception {
        return tidy(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
    }

    /**
     * 샘플 데이터로 화면 API를 모두 불러 보고 명세와 다른 곳을 모은다.
     * 선수마다 선수 페이지·같은 경기·비교를, 기사 목록의 기사마다 기사 페이지를, 최근 경기마다 경기 상세를 부른다.
     */
    static List<String> crawl(MockMvc mvc, String specText) throws Exception {
        JsonNode spec = JSON.readTree(specText);
        List<String> errors = new ArrayList<>();
        JsonNode home = call(mvc, spec, "/api/home", "/api/home", errors);
        JsonNode players = call(mvc, spec, "/api/players", "/api/players", errors);
        call(mvc, spec, "/api/players/timeline", "/api/players/timeline", errors);
        call(mvc, spec, "/api/records", "/api/records", errors);
        List<String> ids = new ArrayList<>();
        for (JsonNode player : players) {
            String id = player.path("id").asText();
            ids.add(id);
            call(mvc, spec, "/api/players/{id}", "/api/players/" + id, errors);
            call(mvc, spec, "/api/players/{id}/same-games", "/api/players/" + id + "/same-games", errors);
            call(mvc, spec, "/api/players/{id}/compare", "/api/players/" + id + "/compare", errors);
        }
        call(mvc, spec, "/api/my", "/api/my?ids=" + String.join(",", ids) + "&days=30", errors);
        JsonNode news = call(mvc, spec, "/api/news", "/api/news", errors);
        for (JsonNode item : news.path("items")) {
            call(mvc, spec, "/api/news/{id}", "/api/news/" + item.path("id").asText(), errors);
        }
        for (JsonNode line : home.path("recent").path("lines")) {
            if (line.path("gameId").canConvertToLong()) {
                call(mvc, spec, "/api/players/{id}/games/{gamePk}",
                        "/api/players/" + line.path("playerId").asText() + "/games/" + line.path("gameId").asText(), errors);
            }
        }
        call(mvc, spec, "/api/players/{id}", "/api/players/999999999", errors);
        return errors;
    }

    private static JsonNode call(MockMvc mvc, JsonNode spec, String template, String url, List<String> errors)
            throws Exception {
        MockHttpServletResponse response = mvc.perform(get(url)).andReturn().getResponse();
        JsonNode body = JSON.readTree(response.getContentAsByteArray());
        for (String error : mismatches(spec, template, "get", response.getStatus(), body)) {
            errors.add(url + " " + error);
        }
        return body;
    }

    /**
     * 실제 응답이 명세와 다른 곳을 모은다. 필수 필드가 없거나, 명세에 없는 필드가 있거나,
     * null이 아니라고 적은 필드가 null이거나, 타입이 다르면 그 자리(JSON 경로)를 적는다.
     */
    static List<String> mismatches(JsonNode spec, String path, String method, int status, JsonNode body) {
        JsonNode schema = spec.path("paths").path(path).path(method).path("responses").path(String.valueOf(status))
                .path("content").path("application/json").path("schema");
        List<String> errors = new ArrayList<>();
        if (schema.isMissingNode()) {
            errors.add(method + " " + path + " " + status + ": 명세에 이 응답이 없다");
            return errors;
        }
        check(spec, schema, body, "$", errors);
        return errors;
    }

    private static void check(JsonNode spec, JsonNode schema, JsonNode value, String at, List<String> errors) {
        if (schema.has("$ref")) {
            String name = schema.get("$ref").asText().substring(SCHEMA_REF.length());
            check(spec, spec.path("components").path("schemas").path(name), value, at, errors);
            return;
        }
        for (String combinator : List.of("anyOf", "oneOf")) {
            if (schema.has(combinator)) {
                int matched = 0;
                for (JsonNode branch : schema.get(combinator)) {
                    List<String> branchErrors = new ArrayList<>();
                    check(spec, branch, value, at, branchErrors);
                    if (branchErrors.isEmpty()) {
                        matched++;
                    }
                }
                boolean ok = combinator.equals("oneOf") ? matched == 1 : matched >= 1;
                if (!ok) {
                    errors.add(at + ": " + combinator + " 중 맞는 모양이 " + matched + "개다");
                }
                return;
            }
        }
        JsonNode type = schema.get("type");
        if (type == null) {
            return;
        }
        Set<String> types = new LinkedHashSet<>();
        if (type.isArray()) {
            type.forEach(t -> types.add(t.asText()));
        } else {
            types.add(type.asText());
        }
        if (value.isNull()) {
            if (!types.contains("null")) {
                errors.add(at + ": null인데 명세는 null이 아니라고 한다");
            }
            return;
        }
        if (types.contains("object") && value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.asText())) {
                    errors.add(at + "." + required.asText() + ": 필수 필드가 응답에 없다");
                }
            }
            JsonNode props = schema.path("properties");
            JsonNode extra = schema.get("additionalProperties");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode prop = props.get(field.getKey());
                if (prop == null && extra != null && extra.isObject()) {
                    prop = extra;
                }
                if (prop == null) {
                    errors.add(at + "." + field.getKey() + ": 명세에 없는 필드가 응답에 있다");
                } else {
                    check(spec, prop, field.getValue(), at + "." + field.getKey(), errors);
                }
            }
            return;
        }
        if (types.contains("array") && value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                check(spec, schema.path("items"), value.get(i), at + "[" + i + "]", errors);
            }
            return;
        }
        boolean ok = types.contains("string") && value.isTextual()
                || types.contains("integer") && value.isIntegralNumber()
                || types.contains("number") && value.isNumber()
                || types.contains("boolean") && value.isBoolean();
        if (!ok) {
            errors.add(at + ": 타입이 명세(" + types + ")와 다르다: " + value.getNodeType());
            return;
        }
        if (schema.has("enum") && value.isTextual()) {
            boolean listed = false;
            for (JsonNode allowed : schema.get("enum")) {
                listed |= allowed.asText().equals(value.asText());
            }
            if (!listed) {
                errors.add(at + ": 명세에 없는 값이다: " + value.asText());
            }
        }
    }
}
