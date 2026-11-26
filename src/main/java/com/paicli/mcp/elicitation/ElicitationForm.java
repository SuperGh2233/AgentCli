package com.paicli.mcp.elicitation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the deliberately small JSON Schema subset used by MCP form elicitation.
 * This is not a general JSON Schema validator.
 */
public final class ElicitationForm {
    private static final int MAX_FIELDS = 12;
    private static final int MAX_OPTIONS = 20;
    private static final int MAX_TEXT = 2048;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|secret|token|api.?key|access.?key|private.?key|credential|"
                    + "credit.?card|card.?number|cvv|cvc|payment)");
    private static final Set<String> ROOT_KEYS = Set.of(
            "type", "title", "description", "properties", "required", "additionalProperties");
    private static final Set<String> COMMON_KEYS = Set.of("type", "title", "description", "default");
    private static final Set<String> STRING_KEYS = Set.of(
            "type", "title", "description", "default", "enum", "oneOf",
            "minLength", "maxLength", "format");
    private static final Set<String> NUMBER_KEYS = Set.of(
            "type", "title", "description", "default", "minimum", "maximum");
    private static final Set<String> BOOLEAN_KEYS = COMMON_KEYS;
    private static final Set<String> ARRAY_KEYS = Set.of(
            "type", "title", "description", "default", "items", "minItems", "maxItems");

    private ElicitationForm() { }

    public static void validateSchema(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            throw invalid("requestedSchema 必须是 object");
        }
        rejectUnknown(schema, ROOT_KEYS, "requestedSchema");
        boundedText(schema.get("title"), 100, "title");
        boundedText(schema.get("description"), 500, "description");
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()
                || properties.size() > MAX_FIELDS) {
            throw invalid("表单字段数量必须在 1 到 " + MAX_FIELDS + " 之间");
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && !additional.isBoolean()) {
            throw invalid("additionalProperties 必须为 false");
        }
        if (additional != null && additional.asBoolean()) {
            throw invalid("不支持 additionalProperties=true");
        }
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray() || required.size() > MAX_FIELDS) {
                throw invalid("required 必须是有限字符串数组");
            }
            for (JsonNode item : required) {
                if (!item.isTextual() || !properties.has(item.asText())) {
                    throw invalid("required 包含无效字段");
                }
            }
        }
        Iterator<String> names = properties.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode field = properties.get(name);
            String sensitiveContext = name + " " + (field == null ? "" : field.path("title").asText("")
                    + " " + field.path("description").asText(""));
            if (name.isBlank() || name.length() > 80
                    || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                    || SENSITIVE.matcher(sensitiveContext).find()) {
                throw invalid("表单字段名无效或疑似要求敏感信息: " + safeName(name));
            }
            validateField(field, name);
        }
    }

    private static void validateField(JsonNode field, String name) {
        if (field == null || !field.isObject()) throw invalid("字段 schema 必须是对象: " + safeName(name));
        String type = field.path("type").asText("");
        Set<String> allowed = switch (type) {
            case "string" -> STRING_KEYS;
            case "number", "integer" -> NUMBER_KEYS;
            case "boolean" -> BOOLEAN_KEYS;
            case "array" -> ARRAY_KEYS;
            default -> throw invalid("不支持字段类型: " + safeName(name));
        };
        rejectUnknown(field, allowed, "字段 " + safeName(name));
        boundedText(field.get("title"), 100, "title");
        boundedText(field.get("description"), 500, "description");
        switch (type) {
            case "string" -> validateString(field, name);
            case "number", "integer" -> validateNumber(field, name, type);
            case "boolean" -> {
                JsonNode value = field.get("default");
                if (value != null && !value.isBoolean()) throw invalid("boolean default 类型错误: " + safeName(name));
            }
            case "array" -> validateArray(field, name);
            default -> throw invalid("不支持字段类型: " + safeName(name));
        }
    }

    private static void validateString(JsonNode field, String name) {
        integerRange(field.get("minLength"), 0, MAX_TEXT, "minLength");
        integerRange(field.get("maxLength"), 1, MAX_TEXT, "maxLength");
        if (field.has("minLength") && field.has("maxLength")
                && field.path("minLength").asInt() > field.path("maxLength").asInt()) {
            throw invalid("minLength 不能大于 maxLength: " + safeName(name));
        }
        JsonNode format = field.get("format");
        if (format != null && (!format.isTextual()
                || !Set.of("email", "uri", "date", "date-time").contains(format.asText()))) {
            throw invalid("不支持字符串 format: " + safeName(name));
        }
        JsonNode enumeration = field.get("enum");
        JsonNode oneOf = field.get("oneOf");
        if (enumeration != null && oneOf != null) throw invalid("enum 与 oneOf 不能同时出现: " + safeName(name));
        if (enumeration != null) validateStringEnum(enumeration, name);
        if (oneOf != null) validateStringOneOf(oneOf, name);
        JsonNode defaultValue = field.get("default");
        if (defaultValue != null) {
            if (!defaultValue.isTextual() || defaultValue.asText().codePoints().anyMatch(Character::isISOControl))
                throw invalid("string default 类型错误: " + safeName(name));
            int length = defaultValue.asText().codePointCount(0, defaultValue.asText().length());
            if (length < field.path("minLength").asInt(0) || length > field.path("maxLength").asInt(MAX_TEXT))
                throw invalid("string default 超出长度约束: " + safeName(name));
            if (!choices(field).isEmpty() && !choices(field).contains(defaultValue.asText()))
                throw invalid("string default 不在枚举范围内: " + safeName(name));
            validateStringFormat(field.path("format").asText(""), defaultValue.asText(), name);
        }
    }

    private static void validateNumber(JsonNode field, String name, String type) {
        finiteNumber(field.get("minimum"), "minimum");
        finiteNumber(field.get("maximum"), "maximum");
        if (field.has("minimum") && field.has("maximum")
                && field.path("minimum").decimalValue().compareTo(field.path("maximum").decimalValue()) > 0) {
            throw invalid("minimum 不能大于 maximum: " + safeName(name));
        }
        JsonNode value = field.get("default");
        if (value != null) {
            if (!value.isNumber() || ("integer".equals(type) && !value.isIntegralNumber()))
                throw invalid("数值 default 类型错误: " + safeName(name));
            if (field.has("minimum") && value.decimalValue().compareTo(field.path("minimum").decimalValue()) < 0)
                throw invalid("数值 default 小于 minimum: " + safeName(name));
            if (field.has("maximum") && value.decimalValue().compareTo(field.path("maximum").decimalValue()) > 0)
                throw invalid("数值 default 大于 maximum: " + safeName(name));
        }
    }

    private static void validateArray(JsonNode field, String name) {
        JsonNode items = field.get("items");
        if (items == null || !items.isObject()) throw invalid("只支持字符串枚举数组: " + safeName(name));
        JsonNode itemEnum = items.get("enum");
        JsonNode itemAnyOf = items.get("anyOf");
        if (itemEnum != null && itemAnyOf != null) throw invalid("数组 items 不能同时声明 enum 和 anyOf");
        if (itemEnum != null) {
            if (!"string".equals(items.path("type").asText())) throw invalid("数组 enum items 必须声明 string 类型");
            rejectUnknown(items, Set.of("type", "enum"), "数组 items");
            validateStringEnum(itemEnum, name);
        } else if (itemAnyOf != null) {
            rejectUnknown(items, Set.of("anyOf"), "数组 items");
            validateStringOneOf(itemAnyOf, name);
        } else {
            throw invalid("数组 items 必须包含 enum 或 anyOf");
        }
        integerRange(field.get("minItems"), 0, MAX_OPTIONS, "minItems");
        integerRange(field.get("maxItems"), 1, MAX_OPTIONS, "maxItems");
        if (field.has("minItems") && field.has("maxItems")
                && field.path("minItems").asInt() > field.path("maxItems").asInt()) {
            throw invalid("minItems 不能大于 maxItems: " + safeName(name));
        }
        java.util.List<String> itemChoices = choices(items);
        if (itemChoices.stream().anyMatch(option -> option.contains(",")))
            throw invalid("多选枚举值不能包含逗号: " + safeName(name));
        JsonNode value = field.get("default");
        if (value != null) {
            if (!value.isArray()) throw invalid("数组 default 必须是数组: " + safeName(name));
            java.util.Set<String> allowed = new java.util.HashSet<>(itemChoices);
            if (value.size() < field.path("minItems").asInt(0)
                    || value.size() > field.path("maxItems").asInt(MAX_OPTIONS))
                throw invalid("数组 default 数量超出约束: " + safeName(name));
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonNode item : value) {
                if (!item.isTextual() || !allowed.contains(item.asText()) || !seen.add(item.asText())) {
                    throw invalid("数组 default 不在允许选项中或包含重复值: " + safeName(name));
                }
            }
        }
    }

    private static java.util.List<String> choices(JsonNode field) {
        java.util.List<String> values = new java.util.ArrayList<>();
        JsonNode enumeration = field.get("enum");
        if (enumeration != null && enumeration.isArray()) enumeration.forEach(v -> values.add(v.asText()));
        JsonNode oneOf = field.get("oneOf");
        if (oneOf != null && oneOf.isArray()) oneOf.forEach(v -> values.add(v.path("const").asText()));
        JsonNode anyOf = field.get("anyOf");
        if (anyOf != null && anyOf.isArray()) anyOf.forEach(v -> values.add(v.path("const").asText()));
        return values;
    }

    private static void validateStringFormat(String format, String value, String name) {
        try {
            switch (format) {
                case "", "unknown" -> { }
                case "email" -> {
                    if (!value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
                        throw invalid("string default 邮箱格式错误: " + safeName(name));
                }
                case "uri" -> {
                    if (!java.net.URI.create(value).isAbsolute())
                        throw invalid("string default 必须是绝对 URI: " + safeName(name));
                }
                case "date" -> java.time.LocalDate.parse(value);
                case "date-time" -> java.time.OffsetDateTime.parse(value);
                default -> throw invalid("不支持字符串 format: " + safeName(name));
            }
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException iae && iae.getMessage() != null
                    && iae.getMessage().startsWith("string default")) throw iae;
            throw invalid("string default 格式错误: " + safeName(name));
        }
    }

    private static void validateStringEnum(JsonNode values, String name) {
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > MAX_OPTIONS) {
            throw invalid("枚举选项数量必须在 1 到 " + MAX_OPTIONS + " 之间: " + safeName(name));
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().length() > 200
                    || hasUnsafeDisplayCodePoint(value.asText())) {
                throw invalid("枚举值必须是短字符串: " + safeName(name));
            }
        }
    }

    private static void validateStringOneOf(JsonNode values, String name) {
        if (!values.isArray() || values.isEmpty() || values.size() > MAX_OPTIONS) {
            throw invalid("oneOf 选项数量无效: " + safeName(name));
        }
        for (JsonNode value : values) {
            if (!value.isObject() || !value.path("const").isTextual()
                    || value.path("const").asText().length() > 200
                    || hasUnsafeDisplayCodePoint(value.path("const").asText())
                    || (value.has("title") && (!value.path("title").isTextual() || value.path("title").asText().length() > 100
                            || hasUnsafeDisplayCodePoint(value.path("title").asText())))) {
                throw invalid("oneOf 只支持字符串 const/title: " + safeName(name));
            }
            rejectUnknown(value, Set.of("const", "title"), "oneOf");
        }
    }

    private static void integerRange(JsonNode value, int min, int max, String label) {
        if (value == null) return;
        if (!value.isIntegralNumber()
                || value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(min)) < 0
                || value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(max)) > 0) {
            throw invalid(label + " 超出支持范围");
        }
    }

    private static void finiteNumber(JsonNode value, String label) {
        if (value != null && (!value.isNumber() || !Double.isFinite(value.asDouble()))) {
            throw invalid(label + " 必须是有限数字");
        }
    }

    private static void boundedText(JsonNode value, int max, String label) {
        if (value != null && (!value.isTextual() || value.asText().length() > max
                || hasUnsafeDisplayCodePoint(value.asText()))) {
            throw invalid(label + " 无效或过长");
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String label) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (!allowed.contains(key)) throw invalid(label + " 包含不支持的 schema 关键字: " + key);
        }
    }

    private static boolean hasUnsafeDisplayCodePoint(String value) {
        return value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }

    private static String safeName(String name) {
        return name == null ? "(unknown)" : name.replaceAll("[\\p{Cntrl}]", "?");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
