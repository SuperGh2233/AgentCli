package com.paicli.mcp.elicitation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Objects;

public record ElicitationResponse(Action action, JsonNode content) {
    public enum Action { ACCEPT, DECLINE, CANCEL }
    public ElicitationResponse {
        Objects.requireNonNull(action);
        if (content != null && (action != Action.ACCEPT || !content.isObject()))
            throw new IllegalArgumentException("只有表单接受响应可以带对象 content");
        content = content == null ? null : content.deepCopy();
    }
    @Override public JsonNode content() { return content == null ? null : content.deepCopy(); }
    public static ElicitationResponse cancel() { return new ElicitationResponse(Action.CANCEL, null); }
    public static ElicitationResponse decline() { return new ElicitationResponse(Action.DECLINE, null); }
    public ObjectNode toJson() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("action", action.name().toLowerCase(Locale.ROOT));
        if (content != null) result.set("content", content.deepCopy());
        return result;
    }
    @Override public String toString() { return "ElicitationResponse[" + action + "]"; }
}
