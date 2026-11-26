package com.paicli.llm.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 暴露给模型的一个工具：名字、说明和参数的 JSON Schema。 */
public record ToolSpec(String name, String description, JsonNode parametersSchema) {
    public ToolSpec {
        Objects.requireNonNull(name, "tool name");
    }
}
