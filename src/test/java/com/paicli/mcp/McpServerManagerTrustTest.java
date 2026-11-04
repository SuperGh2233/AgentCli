package com.paicli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.config.McpTrustStore;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.transport.McpPidFile;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServerManagerTrustTest {
    @TempDir Path temp;
    private McpServerManager manager;

    private McpServerManager manager(ToolRegistry registry, McpTrustStore trustStore) {
        return new McpServerManager(registry, temp,
                new McpConfigLoader(temp.resolve("user.json"), temp.resolve("project.json"), temp),
                trustStore, new McpPidFile(temp.resolve("mcp-pids.json")));
    }

    @SuppressWarnings("unchecked")
    private void install(McpServer server) throws Exception {
        Field field = McpServerManager.class.getDeclaredField("servers");
        field.setAccessible(true);
        Map<String, McpServer> servers = (Map<String, McpServer>) field.get(manager);
        servers.put(server.name(), server);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.close();
    }

    @Test
    void runtimeArgsOverrideInvalidatesProjectTrustBeforeRestartCanLaunch() throws Exception {
        McpTrustStore trustStore = new McpTrustStore(temp.resolve("state/mcp-trust.json"));
        ToolRegistry registry = new ToolRegistry();
        manager = manager(registry, trustStore);

        Path configFile = temp.resolve("project/.paicli/mcp.json");
        McpServerConfig config = new McpServerConfig();
        config.setCommand("npx");
        config.setArgs(List.of("server"));
        config.markProjectScoped(configFile, McpConfigLoader.fingerprint(config));
        String original = config.projectFingerprint();
        trustStore.trust(configFile, "repo", original);
        McpServer server = new McpServer("repo", config);
        server.status(McpServerStatus.READY);
        install(server);

        String result = manager.restartWithArgs("repo", List.of("server", "--changed"));

        assertTrue(result.contains("/mcp trust repo"), result);
        assertNotEquals(original, config.projectFingerprint());
        assertEquals(config.projectFingerprint(), config.configFingerprint());
        assertFalse(trustStore.isTrusted(configFile, "repo", config.projectFingerprint()));
        assertEquals(McpServerStatus.UNTRUSTED, server.status());
    }

    @Test
    void untrustRevokesProjectServerAndUnregistersItsTools() throws Exception {
        McpTrustStore trustStore = new McpTrustStore(temp.resolve("state/mcp-trust.json"));
        ToolRegistry registry = new ToolRegistry();
        manager = manager(registry, trustStore);

        Path configFile = temp.resolve("project/.paicli/mcp.json");
        McpServerConfig config = new McpServerConfig();
        config.setCommand("npx");
        config.setArgs(List.of("server"));
        config.markProjectScoped(configFile, McpConfigLoader.fingerprint(config));
        trustStore.trust(configFile, "repo", config.projectFingerprint());

        McpServer server = new McpServer("repo", config);
        server.status(McpServerStatus.READY);
        McpToolDescriptor descriptor = new McpToolDescriptor("repo", "echo", "mcp__repo__echo",
                "echo", new ObjectMapper().createObjectNode());
        server.tools(List.of(descriptor));
        registry.registerMcpToolOutput(descriptor, ignored -> ToolOutput.text("ok"));
        install(server);

        String result = manager.untrust("repo");

        assertTrue(result.contains("已撤销"), result);
        assertFalse(trustStore.isTrusted(configFile, "repo", config.projectFingerprint()));
        assertEquals(McpServerStatus.UNTRUSTED, server.status());
        assertFalse(registry.hasTool("mcp__repo__echo"));
        assertTrue(manager.restart("repo").contains("/mcp trust repo"));
    }

    @Test
    void permissionBindingRequiresTrustedReadyServerAndCarriesCurrentFingerprint() throws Exception {
        McpTrustStore trustStore = new McpTrustStore(temp.resolve("state/mcp-trust.json"));
        manager = manager(new ToolRegistry(), trustStore);
        Path configFile = temp.resolve("project/.paicli/mcp.json");
        McpServerConfig config = new McpServerConfig();
        config.setCommand("npx");
        config.setArgs(List.of("server"));
        config.markProjectScoped(configFile, McpConfigLoader.fingerprint(config));
        trustStore.trust(configFile, "repo", config.projectFingerprint());
        McpServer server = new McpServer("repo", config);
        server.status(McpServerStatus.READY);
        install(server);

        McpPermissionBinding binding = manager.permissionBinding("repo");
        assertNotNull(binding);
        assertEquals(config.projectFingerprint(), binding.configFingerprint());
        assertTrue(binding.trusted());
        assertTrue(binding.ready());
        assertTrue(binding.projectScoped());
        assertTrue(binding.usableForPermanentAllow());

        assertTrue(manager.untrust("repo").contains("已撤销"));
        McpPermissionBinding revoked = manager.permissionBinding("repo");
        assertFalse(revoked.trusted());
        assertFalse(revoked.ready());
        assertFalse(revoked.usableForPermanentAllow());
    }

    @Test
    void untrustStorageFailureStillStopsAndBlocksCurrentSessionRestart() throws Exception {
        Path trustFile = temp.resolve("state/mcp-trust.json");
        Files.createDirectories(trustFile.getParent());
        Files.writeString(trustFile, "{broken");
        McpTrustStore trustStore = new McpTrustStore(trustFile);
        ToolRegistry registry = new ToolRegistry();
        manager = manager(registry, trustStore);

        Path configFile = temp.resolve("project/.paicli/mcp.json");
        McpServerConfig config = new McpServerConfig();
        config.setCommand("npx");
        config.setArgs(List.of("server"));
        config.markProjectScoped(configFile, McpConfigLoader.fingerprint(config));
        McpServer server = new McpServer("repo", config);
        server.status(McpServerStatus.READY);
        McpToolDescriptor descriptor = new McpToolDescriptor("repo", "echo", "mcp__repo__echo",
                "echo", new ObjectMapper().createObjectNode());
        server.tools(List.of(descriptor));
        registry.registerMcpToolOutput(descriptor, ignored -> ToolOutput.text("ok"));
        install(server);

        String result = manager.untrust("repo");

        assertTrue(result.contains("当前会话已停止并阻止该 server 重启"), result);
        assertEquals(McpServerStatus.UNTRUSTED, server.status());
        assertFalse(registry.hasTool("mcp__repo__echo"));
        assertFalse(manager.permissionBinding("repo").usableForPermanentAllow());
        assertTrue(manager.restart("repo").contains("/mcp trust repo"));
        assertEquals(McpServerStatus.UNTRUSTED, server.status());
    }

    @Test
    void userConfigBindingIsTrustedByOwnerButStillFingerprintBound() throws Exception {
        manager = manager(new ToolRegistry(), new McpTrustStore(temp.resolve("state/mcp-trust.json")));
        McpServerConfig config = new McpServerConfig();
        config.setUrl("https://example.invalid/mcp");
        config.markUserScoped(McpConfigLoader.fingerprint(config));
        McpServer server = new McpServer("personal", config);
        server.status(McpServerStatus.READY);
        install(server);

        McpPermissionBinding binding = manager.permissionBinding("personal");
        assertTrue(binding.trusted());
        assertTrue(binding.ready());
        assertFalse(binding.projectScoped());
        assertTrue(binding.usableForPermanentAllow());
    }
}
