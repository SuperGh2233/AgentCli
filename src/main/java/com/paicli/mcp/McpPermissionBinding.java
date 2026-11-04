package com.paicli.mcp;

/**
 * Read-only snapshot used by HITL permission evaluation.
 * A binding is usable only when the active server is ready, trusted and has a stable config fingerprint.
 */
public record McpPermissionBinding(
        String serverName,
        String configFingerprint,
        boolean trusted,
        boolean ready,
        boolean projectScoped
) {
    public boolean usableForPermanentAllow() {
        return trusted && ready && configFingerprint != null
                && configFingerprint.matches("[0-9a-f]{64}");
    }
}
