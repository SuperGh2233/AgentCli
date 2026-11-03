package com.paicli.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpOAuthConfigTest {
    @TempDir Path temp;
    McpServerConfig config() {var c=new McpServerConfig();c.setUrl("https://mcp.example/mcp");var o=new McpOAuthConfig();o.setClientId("client");o.setScopes(List.of("read"));c.setOauth(o);return c;}
    @Test void validAndFingerprintIncludesClientAndScopes() {
        var loader=new McpConfigLoader(temp);var c=config();loader.prepare(c);String original=McpConfigLoader.fingerprint(c);
        c.getOauth().setClientId("other");assertNotEquals(original,McpConfigLoader.fingerprint(c));
        c.getOauth().setClientId("client");c.getOauth().setScopes(List.of("write"));assertNotEquals(original,McpConfigLoader.fingerprint(c));
    }
    @Test void refusesStdioStaticAuthorizationMissingClientAndInvalidScopes() {
        var loader=new McpConfigLoader(temp);var c=config();c.setCommand("node");c.setUrl(null);assertThrows(IllegalArgumentException.class,()->loader.prepare(c));
        c.setCommand(null);c.setUrl("https://mcp.example/mcp");c.setHeaders(Map.of("AUTHORIZATION","secret"));assertThrows(IllegalArgumentException.class,()->loader.prepare(c));
        c.setHeaders(Map.of());c.getOauth().setClientId(" ");assertThrows(IllegalArgumentException.class,()->loader.prepare(c));
        c.getOauth().setClientId("client");c.getOauth().setScopes(List.of("read write"));assertThrows(IllegalArgumentException.class,()->loader.prepare(c));
    }
    @Test void rejectsSecretsAndUnknownOauthFields() {
        for(String field:List.of("client_secret","clientSecret","registration_endpoint","deviceCode"))
            assertThrows(Exception.class,()->new ObjectMapper().readValue("{\"url\":\"https://mcp.example\",\"oauth\":{\"clientId\":\"c\",\""+field+"\":\"secret\"}}",McpServerConfig.class));
    }
}
