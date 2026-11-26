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
        config.markProjectScoped(configFile, trustStore.fingerprint(config.sourcePayload(), true));
        String original = config.projectFingerprint();
        trustStore.trust(configFile, "repo", original);
        McpServer server = new McpServer("repo", config);
        server.status(McpServerStatus.READY);
        install(server);

        String result = manager.restartWithArgs("repo", List.of("server", "--changed"));

        assertTrue(result.contains("/mcp trust repo"), result);
        assertNotEquals(original, config.projectFingerprint());
        assertNull(config.configFingerprint());
        assertFalse(trustStore.isTrusted(configFile, "repo", config.projectFingerprint()));
        assertEquals(McpServerStatus.UNTRUSTED, server.status());
        config.overrideArgs(List.of("server"));
        assertEquals(original, trustStore.fingerprint(config.sourcePayload(), false));
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
        config.markProjectScoped(configFile, trustStore.fingerprint(config.sourcePayload(), true));
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
        Files.writeString(temp.resolve("project.json"), """
                {"mcpServers":{"repo":{"command":"node","args":["server"]}}}
                """);
        McpServerConfig config = readyConfig("repo", trustStore);
        trustStore.trust(config.projectConfigFile(), "repo", config.projectFingerprint());

        McpPermissionBinding binding = manager.permissionBinding("repo");
        assertNotNull(binding);
        assertEquals(config.configFingerprint(), binding.configFingerprint());
        assertTrue(binding.current());
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
        config.markProjectScoped(configFile, null);
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
        McpTrustStore store = new McpTrustStore(temp.resolve("state/mcp-trust.json"));
        manager = manager(new ToolRegistry(), store);
        Files.writeString(temp.resolve("user.json"), """
                {"mcpServers":{"personal":{"url":"https://example.invalid/mcp"}}}
                """);
        readyConfig("personal", store);

        McpPermissionBinding binding = manager.permissionBinding("personal");
        assertTrue(binding.trusted());
        assertTrue(binding.ready());
        assertFalse(binding.projectScoped());
        assertTrue(binding.usableForPermanentAllow());
    }

    // Model an already initialized server without spawning a process or connecting to a URL.
    private McpServerConfig readyConfig(String name, McpTrustStore store) throws Exception {
        manager.loadConfiguredServers();
        McpServer server = manager.server(name);
        McpServerConfig config = server.config();
        new McpConfigLoader(temp.resolve("user.json"), temp.resolve("project.json"), temp).prepare(config);
        config.effectiveFingerprint(store.fingerprint(McpConfigLoader.fingerprintPayload(config), false));
        server.status(McpServerStatus.READY);
        return config;
    }

    @Test
    void fileAndEffectiveChangesMakeBindingStaleWithoutChangingReadyOrTrust() throws Exception {
        String variable = "PAICLI_C2A_MANAGER_VALUE";
        String previous = System.getProperty(variable);
        McpTrustStore store = new McpTrustStore(temp.resolve("state/mcp-trust.json"));
        manager = manager(new ToolRegistry(), store);
        String json = "{\"mcpServers\":{\"repo\":{\"command\":\"node\",\"args\":[\"${" + variable + "}\"]}}}";
        Files.writeString(temp.resolve("project.json"), json);
        try {
            System.setProperty(variable, "first");
            McpServerConfig config = readyConfig("repo", store);
            store.trust(config.projectConfigFile(), "repo", config.projectFingerprint());
            assertTrue(manager.permissionBinding("repo").current());
            System.setProperty(variable, "second");
            McpPermissionBinding stale = manager.permissionBinding("repo");
            assertFalse(stale.current());
            assertTrue(stale.ready());
            assertTrue(stale.trusted());
            assertFalse(stale.usableForPermanentAllow());
            System.clearProperty(variable);
            assertFalse(manager.permissionBinding("repo").current());
            System.setProperty(variable, "first");
            assertTrue(manager.permissionBinding("repo").current());
            Files.writeString(temp.resolve("project.json"), json.replace("node", "other"));
            assertFalse(manager.permissionBinding("repo").current());
            Files.writeString(temp.resolve("project.json"), "{broken");
            assertFalse(manager.permissionBinding("repo").current());
            Files.delete(temp.resolve("project.json"));
            assertFalse(manager.permissionBinding("repo").current());
        } finally {
            if (previous == null) System.clearProperty(variable);
            else System.setProperty(variable, previous);
        }
    }

    @Test
    void projectDotEnvChangesInvalidateUserBinding() throws Exception {
        McpTrustStore store = new McpTrustStore(temp.resolve("state/mcp-trust.json"));
        manager = manager(new ToolRegistry(), store);
        Files.writeString(temp.resolve("user.json"), """
                {"mcpServers":{"personal":{"command":"node","env":{"VALUE":"${PAICLI_C2A_DOTENV_VALUE}"}}}}
                """);
        Files.writeString(temp.resolve(".env"), "PAICLI_C2A_DOTENV_VALUE=first");
        readyConfig("personal", store);
        assertTrue(manager.permissionBinding("personal").current());
        Files.writeString(temp.resolve(".env"), "PAICLI_C2A_DOTENV_VALUE=second");
        McpPermissionBinding stale = manager.permissionBinding("personal");
        assertFalse(stale.current());
        assertTrue(stale.trusted());
        assertTrue(stale.ready());
        assertFalse(stale.projectScoped());
    }

    @Test
    void unavailableHmacLeavesUserBindingUnusableAndProjectUntrusted() throws Exception {
        Path file = temp.resolve("state/trust.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{broken");
        McpTrustStore store = new McpTrustStore(file);
        manager = manager(new ToolRegistry(), store);
        Files.writeString(temp.resolve("user.json"), """
                {"mcpServers":{"personal":{"command":"node"}}}
                """);
        Files.writeString(temp.resolve("project.json"), """
                {"mcpServers":{"repo":{"command":"node"}}}
                """);
        manager.loadConfiguredServers();
        assertNull(manager.server("repo").config().projectFingerprint());
        assertFalse(manager.permissionBinding("repo").trusted());
        McpServer personal = manager.server("personal");
        personal.status(McpServerStatus.READY);
        assertTrue(manager.permissionBinding("personal").trusted());
        assertFalse(manager.permissionBinding("personal").current());
        assertFalse(manager.permissionBinding("personal").usableForPermanentAllow());
    }

    @Test
    void homeDotEnvAndBuiltInStepChangesAreDetected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("STEP_API_KEY") == null
                || System.getenv("STEP_API_KEY").isBlank());
        String home = System.getProperty("user.home");
        String step = System.getProperty("STEP_API_KEY");
        String value = System.getProperty("PAICLI_C2A_HOME_VALUE");
        Path isolatedHome = temp.resolve("isolated-home");
        Files.createDirectories(isolatedHome);
        try {
            System.setProperty("user.home", isolatedHome.toString());
            System.clearProperty("STEP_API_KEY");
            System.clearProperty("PAICLI_C2A_HOME_VALUE");
            Files.writeString(isolatedHome.resolve(".env"), "PAICLI_C2A_HOME_VALUE=first");
            Files.writeString(temp.resolve("user.json"), """
                    {"mcpServers":{"personal":{"command":"node","args":["${PAICLI_C2A_HOME_VALUE}"]}}}
                    """);
            McpTrustStore store = new McpTrustStore(temp.resolve("state/trust.json"));
            manager = manager(new ToolRegistry(), store);
            readyConfig("personal", store);
            assertTrue(manager.permissionBinding("personal").current());
            Files.writeString(isolatedHome.resolve(".env"), "PAICLI_C2A_HOME_VALUE=second");
            assertFalse(manager.permissionBinding("personal").current());

            System.setProperty("STEP_API_KEY", "fixture-step-one");
            readyConfig("step_search", store);
            assertTrue(manager.permissionBinding("step_search").current());
            System.setProperty("STEP_API_KEY", "fixture-step-two");
            assertFalse(manager.permissionBinding("step_search").current());
            System.clearProperty("STEP_API_KEY");
            assertFalse(manager.permissionBinding("step_search").current());
        } finally {
            System.setProperty("user.home", home);
            if (step == null) System.clearProperty("STEP_API_KEY");
            else System.setProperty("STEP_API_KEY", step);
            if (value == null) System.clearProperty("PAICLI_C2A_HOME_VALUE");
            else System.setProperty("PAICLI_C2A_HOME_VALUE", value);
        }
    }

    @Test
    void runtimeOverrideChangeMakesReadyBindingStaleUntilPreparedAgain() throws Exception {
        McpTrustStore store = new McpTrustStore(temp.resolve("state/trust.json"));
        manager = manager(new ToolRegistry(), store);
        Files.writeString(temp.resolve("user.json"), """
                {"mcpServers":{"personal":{"command":"node","args":["original"]}}}
                """);
        McpServerConfig config = readyConfig("personal", store);
        String original = config.sourceFingerprint();
        config.overrideArgs(List.of("override"));
        assertFalse(manager.permissionBinding("personal").current());
        String changed = store.fingerprint(config.sourcePayload(), false);
        assertNotEquals(original, changed);
        config.markUserScoped(changed);
        new McpConfigLoader(temp.resolve("user.json"), temp.resolve("project.json"), temp).prepare(config);
        config.effectiveFingerprint(store.fingerprint(McpConfigLoader.fingerprintPayload(config), false));
        assertTrue(manager.permissionBinding("personal").current());
        Files.writeString(temp.resolve("user.json"), """
                {"mcpServers":{"personal":{"command":"node","args":["edited-under-override"]}}}
                """);
        assertFalse(manager.permissionBinding("personal").current(), "Raw args edits must remain visible under overrides");
        config.overrideArgs(List.of("original"));
        assertEquals(original, store.fingerprint(config.sourcePayload(), false));
    }

    @Test
    void corruptKeyDoesNotMakeReadyUserBindingCurrentOrOverwriteStorage() throws Exception {
        Path file = temp.resolve("state/trust.json");
        McpTrustStore store = new McpTrustStore(file);
        manager = manager(new ToolRegistry(), store);
        Files.writeString(temp.resolve("user.json"), """
                {"mcpServers":{"personal":{"command":"node"}}}
                """);
        readyConfig("personal", store);
        assertTrue(manager.permissionBinding("personal").current());
        String damaged = "{\"trustedProjectServers\":[],\"fingerprintKey\":null}";
        Files.writeString(file, damaged);
        McpPermissionBinding binding = manager.permissionBinding("personal");
        assertFalse(binding.current());
        assertFalse(binding.usableForPermanentAllow());
        assertTrue(binding.ready());
        assertTrue(binding.trusted());
        assertEquals(damaged, Files.readString(file));
    }

    @Test
    void runtimeEnableOverrideRemainsCurrentAcrossFreshBindingCheck() throws Exception {
        McpTrustStore store = new McpTrustStore(temp.resolve("state/trust.json"));
        manager = manager(new ToolRegistry(), store);
        Files.writeString(temp.resolve("user.json"), """
                {"mcpServers":{"personal":{"command":"node","disabled":true}}}
                """);
        McpServerConfig config = readyConfig("personal", store);
        assertTrue(config.isDisabled());

        config.setDisabled(false);
        new McpConfigLoader(temp.resolve("user.json"), temp.resolve("project.json"), temp).prepare(config);
        config.effectiveFingerprint(store.fingerprint(McpConfigLoader.fingerprintPayload(config), false));
        assertFalse(config.isDisabled());

        McpPermissionBinding binding = manager.permissionBinding("personal");
        assertTrue(binding.current());
        assertTrue(binding.ready());
        assertTrue(binding.usableForPermanentAllow());
    }
}
