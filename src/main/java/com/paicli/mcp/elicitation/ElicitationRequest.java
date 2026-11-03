package com.paicli.mcp.elicitation;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Objects;

/** Immutable UI request. Contains no requestState and is never added to model history. */
public record ElicitationRequest(String serverName, String requestKey, Mode mode,
                                 String message, JsonNode requestedSchema, URI url) {
    public enum Mode { FORM, URL }
    public ElicitationRequest {
        text(serverName, 256); text(requestKey, 256); text(message, 4096);
        Objects.requireNonNull(mode);
        if (mode == Mode.FORM) {
            if (url != null) throw new IllegalArgumentException("表单不能带 URL");
            ElicitationForm.validateSchema(requestedSchema);
            requestedSchema = requestedSchema.deepCopy();
        } else {
            if (requestedSchema != null) throw new IllegalArgumentException("URL 请求不能带表单");
            if (url == null || !url.isAbsolute() || url.getHost() == null || url.getUserInfo() != null
                    || url.getPort() == 0 || url.getPort() > 65535
                    || !("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme())))
                throw new IllegalArgumentException("URL 必须是绝对 http/https 地址且不能含用户凭据");
            text(url.toString(), 4096);
        }
    }
    @Override public JsonNode requestedSchema() { return requestedSchema == null ? null : requestedSchema.deepCopy(); }
    public static ElicitationRequest parse(String server, String key, JsonNode entry) {
        if (entry == null || !entry.isObject() || !"elicitation/create".equals(entry.path("method").asText()))
            throw new IllegalArgumentException("不支持的补充输入 method；只支持 elicitation/create");
        JsonNode p = entry.get("params");
        if (p == null || !p.isObject()) throw new IllegalArgumentException("缺少 elicitation params");
        String mode = p.has("mode") ? requiredText(p, "mode") : "form";
        return switch (mode) {
            case "form" -> {
                if (p.has("url")) throw new IllegalArgumentException("form mode 不能同时包含 url");
                yield new ElicitationRequest(server, key, Mode.FORM, requiredText(p, "message"), p.get("requestedSchema"), null);
            }
            case "url" -> {
                if (p.has("requestedSchema")) throw new IllegalArgumentException("url mode 不能同时包含 requestedSchema");
                yield new ElicitationRequest(server, key, Mode.URL, requiredText(p, "message"), null, URI.create(requiredText(p, "url")));
            }
            default -> throw new IllegalArgumentException("不支持的 elicitation mode");
        };
    }
    private static String requiredText(JsonNode p, String name) {
        if (!p.path(name).isTextual()) throw new IllegalArgumentException("缺少字符串 " + name);
        return p.get(name).textValue();
    }
    static void text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("文本为空、过长或含控制字符");
    }
    @Override public String toString() { return "ElicitationRequest[" + mode + "]"; }
}
