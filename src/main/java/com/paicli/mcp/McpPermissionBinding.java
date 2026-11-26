package com.paicli.mcp;

/**
 * Read-only snapshot used by HITL permission evaluation.
 * A binding is usable only when the active server is ready, trusted and its source/effective fingerprints still match the current configuration.
 */
public record McpPermissionBinding(
        String serverName,
        String configFingerprint,
        boolean trusted,
        boolean ready,
        boolean projectScoped,
        boolean current
) {
    public McpPermissionBinding(String serverName, String configFingerprint, boolean trusted,
                                boolean ready, boolean projectScoped) {
        this(serverName, configFingerprint, trusted, ready, projectScoped, false);
    }

    public boolean usableForPermanentAllow() {
        return current && trusted && ready && configFingerprint != null
                && configFingerprint.matches("[0-9a-f]{64}");
    }
}
