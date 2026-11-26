package com.paicli.mcp.elicitation;

/** User input, separate from tool approval; never auto-accepted by approval policy. */
@FunctionalInterface
public interface McpElicitationHandler {
    McpElicitationHandler CANCEL = request -> ElicitationResponse.cancel();
    ElicitationResponse elicit(ElicitationRequest request);

    default java.util.Set<String> supportedModes() { return java.util.Set.of(); }
}
