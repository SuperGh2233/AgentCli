package com.paicli.mcp.elicitation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.awt.Desktop;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.IDN;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Shared, bounded terminal elicitation flow. It never sends input to the model or reads URL content. */
public final class ElicitationPrompter {
    @FunctionalInterface public interface Input { String read(String prompt) throws IOException; }
    @FunctionalInterface public interface BrowserOpener { void open(URI uri) throws Exception; }

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_INPUT = 2048;

    private ElicitationPrompter() { }

    public static ElicitationResponse prompt(ElicitationRequest request, Appendable output,
                                             Input input, BrowserOpener browser) {
        if (request == null || output == null || input == null || browser == null) {
            return ElicitationResponse.cancel();
        }
        try {
            output.append("\n").append("MCP server ").append(request.serverName())
                    .append(" 请求补充信息：\n").append(request.message()).append("\n");
            if (request.mode() == ElicitationRequest.Mode.URL) {
                return promptUrl(request, output, input, browser);
            }
            return promptForm(request, output, input);
        } catch (Exception e) {
            try { output.append("补充输入未完成，已取消：").append(safeError(e)).append("\n"); }
            catch (IOException ignored) { }
            return ElicitationResponse.cancel();
        }
    }

    public static BrowserOpener desktopBrowser() {
        return uri -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new IOException("当前环境不支持默认浏览器");
            }
            Desktop.getDesktop().browse(uri);
        };
    }

    private static ElicitationResponse promptUrl(ElicitationRequest request, Appendable out,
                                                  Input input, BrowserOpener browser) throws IOException {
        URI uri = request.url();
        String host = uri.getHost();
        out.append("目标主机：").append(host).append("\n");
        String ascii = host.indexOf(':') >= 0 ? host : IDN.toASCII(host);
        boolean punycode = java.util.Arrays.stream(host.split("\\.")).anyMatch(label -> label.toLowerCase(Locale.ROOT).startsWith("xn--"));
        if (punycode || !ascii.equalsIgnoreCase(host)) {
            out.append("⚠️ 域名包含或使用国际化字符，请核对主机名；ASCII 域名：").append(ascii).append("\n");
        }
        out.append("完整地址（不会预取或读取页面）：\n").append(uri.toASCIIString()).append("\n");
        out.append("输入 open 在默认浏览器打开；输入 decline 拒绝；其他输入取消。\n");
        String choice = read(input, "操作> ");
        if (choice == null) return ElicitationResponse.cancel();
        return switch (choice.trim().toLowerCase(Locale.ROOT)) {
            case "decline", "n", "no" -> ElicitationResponse.decline();
            case "open", "y", "yes" -> {
                try {
                    browser.open(uri);
                    yield new ElicitationResponse(ElicitationResponse.Action.ACCEPT, null);
                } catch (Exception e) {
                    out.append("浏览器打开失败，已取消：").append(safeError(e)).append("\n");
                    yield ElicitationResponse.cancel();
                }
            }
            default -> ElicitationResponse.cancel();
        };
    }

    private static ElicitationResponse promptForm(ElicitationRequest request, Appendable out,
                                                    Input input) throws IOException {
        JsonNode schema = request.requestedSchema();
        for (int review = 0; review < MAX_ATTEMPTS; review++) {
            ObjectNode content = JsonNodeFactory.instance.objectNode();
            JsonNode properties = schema.path("properties");
            JsonNode requiredNode = schema.path("required");
            List<String> required = new ArrayList<>();
            if (requiredNode.isArray()) requiredNode.forEach(n -> required.add(n.asText()));
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode field = properties.get(name);
                JsonNode value = collectField(name, field, required.contains(name), out, input);
                if (value == INVALID) return ElicitationResponse.cancel();
                if (value != null) content.set(name, value);
            }
            out.append("\n请检查即将发送给 MCP server 的内容：\n")
                    .append(content.toPrettyString()).append("\n")
                    .append("[submit] 提交  [edit] 重新填写  [decline] 拒绝  [cancel] 取消\n");
            String choice = read(input, "确认> ");
            if (choice == null) return ElicitationResponse.cancel();
            switch (choice.trim().toLowerCase(Locale.ROOT)) {
                case "submit", "y", "yes" -> {
                    return new ElicitationResponse(ElicitationResponse.Action.ACCEPT, content);
                }
                case "decline", "n", "no" -> { return ElicitationResponse.decline(); }
                case "edit", "e" -> { /* collect all fields again */ }
                default -> { return ElicitationResponse.cancel(); }
            }
        }
        out.append("修改次数过多，已取消。\n");
        return ElicitationResponse.cancel();
    }

    private static final JsonNode INVALID = JsonNodeFactory.instance.nullNode();

    private static JsonNode collectField(String name, JsonNode field, boolean required,
                                         Appendable out, Input input) throws IOException {
        String title = field.path("title").asText(name);
        String description = field.path("description").asText("");
        JsonNode defaultValue = field.get("default");
        String type = field.path("type").asText();
        out.append("\n").append(title).append(required ? "（必填）" : "（可选）")
                .append(" [").append(type).append("]");
        if (!description.isBlank()) out.append("\n  ").append(description);
        JsonNode choiceSchema = "array".equals(type) ? field.path("items") : field;
        List<String> choices = choices(choiceSchema);
        if (!choices.isEmpty()) out.append("\n  可选值：").append(String.join(" / ", choiceLabels(choiceSchema)));
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String defaultDisplay = defaultValue == null ? "" : " ["
                    + ("array".equals(type) ? defaultValue.toString() : defaultValue.asText()) + "]";
            String prompt = "\n" + name + defaultDisplay + "> ";
            String raw = read(input, prompt);
            if (raw == null) return INVALID;
            if (raw.length() > MAX_INPUT || raw.codePoints().anyMatch(Character::isISOControl)) {
                out.append("输入过长或含控制字符，请重试。\n");
                continue;
            }
            if (raw.isEmpty()) {
                if (defaultValue != null) return defaultValue.deepCopy();
                if (!required) return null;
                if ("string".equals(type)) {
                    try { return parseValue(name, field, raw); }
                    catch (IllegalArgumentException e) {
                        out.append("输入无效：").append(e.getMessage()).append("\n");
                        continue;
                    }
                }
                if ("array".equals(type) && field.path("minItems").asInt(0) == 0)
                    return JsonNodeFactory.instance.arrayNode();
                out.append("此字段必填。\n");
                continue;
            }
            try {
                return parseValue(name, field, "string".equals(type) ? raw : raw.trim());
            } catch (IllegalArgumentException e) {
                out.append("输入无效：").append(e.getMessage()).append("\n");
            }
        }
        out.append("连续输入无效，已取消。\n");
        return INVALID;
    }

    private static JsonNode parseValue(String name, JsonNode field, String raw) {
        String type = field.path("type").asText();
        return switch (type) {
            case "string" -> {
                int min = field.path("minLength").asInt(0);
                int max = field.path("maxLength").asInt(MAX_INPUT);
                int length = raw.codePointCount(0, raw.length());
                if (length < min || length > max) throw new IllegalArgumentException("长度需在 " + min + " 到 " + max + " 之间");
                List<String> options = choices(field);
                if (!options.isEmpty() && !options.contains(raw)) throw new IllegalArgumentException("请选择列出的选项");
                validateFormat(field.path("format").asText(""), raw);
                yield JsonNodeFactory.instance.textNode(raw);
            }
            case "number", "integer" -> {
                BigDecimal value;
                try { value = new BigDecimal(raw); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("请输入数字"); }
                if ("integer".equals(type)) {
                    try { value.toBigIntegerExact(); }
                    catch (ArithmeticException e) { throw new IllegalArgumentException("请输入整数"); }
                }
                if (field.has("minimum") && value.compareTo(field.path("minimum").decimalValue()) < 0)
                    throw new IllegalArgumentException("不能小于 " + field.path("minimum").asText());
                if (field.has("maximum") && value.compareTo(field.path("maximum").decimalValue()) > 0)
                    throw new IllegalArgumentException("不能大于 " + field.path("maximum").asText());
                yield JsonNodeFactory.instance.numberNode(value);
            }
            case "boolean" -> {
                if (List.of("true", "yes", "y", "1").contains(raw.toLowerCase(Locale.ROOT))) yield JsonNodeFactory.instance.booleanNode(true);
                if (List.of("false", "no", "n", "0").contains(raw.toLowerCase(Locale.ROOT))) yield JsonNodeFactory.instance.booleanNode(false);
                throw new IllegalArgumentException("请输入 true 或 false");
            }
            case "array" -> {
                List<String> options = choices(field.path("items"));
                String[] selected = raw.split(",", -1);
                int min = field.path("minItems").asInt(0);
                int max = field.path("maxItems").asInt(options.size());
                if (selected.length < min || selected.length > max) throw new IllegalArgumentException("选项数量需在 " + min + " 到 " + max + " 之间");
                ArrayNode result = JsonNodeFactory.instance.arrayNode();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (String item : selected) {
                    String choice = item.trim();
                    if (!options.contains(choice)) throw new IllegalArgumentException("不支持选项：" + choice);
                    if (!seen.add(choice)) throw new IllegalArgumentException("不能重复选择：" + choice);
                    result.add(choice);
                }
                yield result;
            }
            default -> throw new IllegalArgumentException("不支持字段类型：" + name);
        };
    }

    private static List<String> choices(JsonNode field) {
        List<String> result = new ArrayList<>();
        JsonNode values = field.get("enum");
        if (values != null && values.isArray()) values.forEach(v -> { if (v.isTextual()) result.add(v.asText()); });
        JsonNode oneOf = field.get("oneOf");
        if (oneOf != null && oneOf.isArray()) oneOf.forEach(v -> {
            if (v.path("const").isTextual()) result.add(v.path("const").asText());
        });
        JsonNode anyOf = field.get("anyOf");
        if (anyOf != null && anyOf.isArray()) anyOf.forEach(v -> {
            if (v.path("const").isTextual()) result.add(v.path("const").asText());
        });
        return result;
    }

    private static List<String> choiceLabels(JsonNode field) {
        List<String> labels = new ArrayList<>();
        JsonNode enumeration = field.get("enum");
        if (enumeration != null && enumeration.isArray()) enumeration.forEach(v -> labels.add(v.asText()));
        JsonNode oneOf = field.get("oneOf");
        JsonNode anyOf = field.get("anyOf");
        JsonNode titled = oneOf != null ? oneOf : anyOf;
        if (titled != null && titled.isArray()) titled.forEach(v -> {
            String value = v.path("const").asText();
            String title = v.path("title").asText("");
            labels.add(title.isBlank() ? value : title + " (" + value + ")");
        });
        return labels;
    }

    private static void validateFormat(String format, String value) {
        try {
            switch (format) {
                case "", "unknown" -> { }
                case "email" -> {
                    if (!value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("请输入有效邮箱");
                }
                case "uri" -> {
                    URI uri = URI.create(value);
                    if (!uri.isAbsolute()) throw new IllegalArgumentException("请输入绝对 URI");
                }
                case "date" -> LocalDate.parse(value);
                case "date-time" -> OffsetDateTime.parse(value);
                default -> throw new IllegalArgumentException("不支持格式：" + format);
            }
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException iae && iae.getMessage() != null
                    && iae.getMessage().startsWith("请输入")) throw iae;
            throw new IllegalArgumentException("格式不正确：" + format);
        }
    }

    private static String read(Input input, String prompt) throws IOException {
        String value = input.read(prompt);
        return value == null ? null : value;
    }

    private static String safeError(Exception e) {
        // Exception messages can contain server-controlled URLs or other untrusted details.
        return "操作失败，请检查请求或终端环境";
    }
}
