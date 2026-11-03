package com.paicli.mcp.protocol;

/** Explicit protocol pin; no negotiation or fallback. */
public enum McpProtocolVersion {
    LEGACY("2025-03-26"), MODERN("2026-07-28");

    private final String value;
    McpProtocolVersion(String value) { this.value = value; }
    public String value() { return value; }
    public boolean modern() { return this == MODERN; }
    /** Copies caller params and preserves unrelated metadata. Legacy traffic is untouched. */
    public com.fasterxml.jackson.databind.JsonNode decorate(com.fasterxml.jackson.databind.JsonNode params) {
        return decorate(params, java.util.Set.of());
    }

    public com.fasterxml.jackson.databind.JsonNode decorate(com.fasterxml.jackson.databind.JsonNode params,
                                                          java.util.Set<String> modes) {
        if (!modern()) return params;
        var decorated = params == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : ((com.fasterxml.jackson.databind.node.ObjectNode) params).deepCopy();
        var meta = decorated.withObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", value);
        var capabilities = meta.withObject("io.modelcontextprotocol/clientCapabilities");
        // Other capabilities belong to the caller; elicitation always reflects this request.
        capabilities.remove("elicitation");
        if (modes != null && (modes.contains("form") || modes.contains("url"))) {
            var elicitation = capabilities.putObject("elicitation");
            for (String mode : java.util.List.of("form", "url"))
                if (modes.contains(mode)) elicitation.putObject(mode);
        }
        var info = meta.putObject("io.modelcontextprotocol/clientInfo");
        info.put("name", "paicli");
        info.put("version", com.paicli.cli.Main.VERSION);
        return decorated;
    }

    public static McpProtocolVersion fromConfig(String value) {
        if (value == null) return LEGACY;
        for (var version : values()) if (version.value.equals(value)) return version;
        throw new IllegalArgumentException("MCP protocolVersion 只支持 2025-03-26 或 2026-07-28");
    }
}
