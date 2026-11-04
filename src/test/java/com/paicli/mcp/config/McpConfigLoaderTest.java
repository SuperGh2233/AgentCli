package com.paicli.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigLoaderTest {

    @Test
    void projectConfigOverridesUserConfig(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Path project = tempDir.resolve("project-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"git":{"command":"uvx","args":["old"]}}}
                """);
        Files.writeString(project, """
                {"mcpServers":{"git":{"command":"uvx","args":["mcp-server-git","--repository","${PROJECT_DIR}"]}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, project, tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        // 项目级配置覆盖用户级
        assertEquals("mcp-server-git", configs.get("git").getArgs().get(0));
        // load 不展开 ${VAR}：展开延迟到 prepare()，单 server 错误才能被隔离
        assertEquals("${PROJECT_DIR}", configs.get("git").getArgs().get(2));
    }

    @Test
    void prepareExpandsProjectDirAndHomeBuiltins(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["-y","server","${PROJECT_DIR}","${HOME}"]}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();
        McpServerConfig fs = configs.get("fs");
        loader.prepare(fs);

        assertEquals(tempDir.toAbsolutePath().normalize().toString(), fs.getArgs().get(2));
        assertEquals(System.getProperty("user.home"), fs.getArgs().get(3));
    }

    @Test
    void prepareExpandsVariablesFromProjectDotEnv(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"remote":{"url":"https://example.com","headers":{"Authorization":"Bearer ${LOCAL_TEST_TOKEN}"}}}}
                """);
        Files.writeString(tempDir.resolve(".env"), "LOCAL_TEST_TOKEN=from_dotenv\n");

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();
        McpServerConfig remote = configs.get("remote");
        loader.prepare(remote);

        assertEquals("Bearer from_dotenv", remote.getHeaders().get("Authorization"));
    }

    @Test
    void loadAddsBuiltInStepSearchWhenStepApiKeyExistsInProjectDotEnv(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["-y","server"]}}}
                """);
        Files.writeString(tempDir.resolve(".env"), "STEP_API_KEY=test-step-key\n");

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        assertTrue(configs.containsKey("step_search"));
        assertEquals("https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp",
                configs.get("step_search").getUrl());
        assertEquals("Bearer test-step-key", configs.get("step_search").getHeaders().get("Authorization"));
    }

    @Test
    void loadDoesNotOverrideExplicitStepSearchConfig(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"step_search":{"url":"https://example.com/custom","headers":{"Authorization":"Bearer custom"}}}}
                """);
        Files.writeString(tempDir.resolve(".env"), "STEP_API_KEY=test-step-key\n");

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        assertEquals("https://example.com/custom", configs.get("step_search").getUrl());
        assertEquals("Bearer custom", configs.get("step_search").getHeaders().get("Authorization"));
    }

    @Test
    void prepareRejectsMissingEnvironmentVariable(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"remote":{"url":"https://example.com","headers":{"Authorization":"Bearer ${NO_SUCH_PAICLI_TOKEN}"}}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        // load 不报错（推迟到 prepare）；prepare 时缺失变量才抛
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loader.prepare(configs.get("remote")));
        assertTrue(ex.getMessage().contains("NO_SUCH_PAICLI_TOKEN"));
    }

    @Test
    void prepareRejectsConfigMissingBothCommandAndUrl(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"broken":{}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        assertThrows(IllegalArgumentException.class, () -> loader.prepare(configs.get("broken")));
    }

    @Test
    void projectServersAreMarkedAndUserServersAreNot(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Path project = tempDir.resolve("project-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["fs"]},"git":{"command":"uvx","args":["old"]}}}
                """);
        // 项目配置用同名 git 覆盖用户配置，还试图在 JSON 里声明自己不是项目级
        Files.writeString(project, """
                {"mcpServers":{"git":{"command":"uvx","args":["evil"],"projectScoped":false,"projectConfigFile":null}}}
                """);

        Map<String, McpServerConfig> configs = new McpConfigLoader(user, project, tempDir).load();

        assertFalse(configs.get("fs").projectScoped());
        assertNull(configs.get("fs").configFingerprint(), "load 不产生有效配置指纹");
        assertTrue(configs.get("git").projectScoped(), "同名覆盖后按项目级处理，不能沿用用户级的信任");
        assertEquals(project.toAbsolutePath().normalize(), configs.get("git").projectConfigFile());
        assertNull(configs.get("git").projectFingerprint(), "source HMAC 由 Manager 标记");
        assertEquals(configs.get("git").projectFingerprint(), configs.get("git").configFingerprint());
    }

    @Test
    void fingerprintCoversEnvUrlAndHeaders() {
        McpServerConfig base = new McpServerConfig();
        base.setCommand("npx");
        base.setArgs(java.util.List.of("server"));
        String original = McpConfigLoader.fingerprint(base);

        McpServerConfig withEnv = new McpServerConfig();
        withEnv.setCommand("npx");
        withEnv.setArgs(java.util.List.of("server"));
        withEnv.setEnv(Map.of("NODE_OPTIONS", "--require ./evil.js"));
        assertNotEquals(original, McpConfigLoader.fingerprint(withEnv));

        McpServerConfig http = new McpServerConfig();
        http.setUrl("https://example.com/mcp");
        McpServerConfig httpWithHeader = new McpServerConfig();
        httpWithHeader.setUrl("https://example.com/mcp");
        httpWithHeader.setHeaders(Map.of("Authorization", "Bearer ${DEEPSEEK_API_KEY}"));
        assertNotEquals(McpConfigLoader.fingerprint(http), McpConfigLoader.fingerprint(httpWithHeader));
    }

    @Test
    void runtimeArgsFingerprintIsStableAndChangesWithOverride() {
        McpServerConfig config = new McpServerConfig();
        config.setCommand("npx");
        config.setArgs(List.of("server"));
        String source = McpConfigLoader.fingerprint(config);
        String first = McpConfigLoader.fingerprintWithRuntimeArgs(source, List.of("--inspect"));
        String same = McpConfigLoader.fingerprintWithRuntimeArgs(source, List.of("--inspect"));
        String changed = McpConfigLoader.fingerprintWithRuntimeArgs(source, List.of("--inspect-brk"));

        assertEquals(first, same);
        assertNotEquals(source, first);
        assertNotEquals(first, changed);
    }

    @Test
    void projectConfigThatIsTheUserConfigStaysUserScoped(@TempDir Path tempDir) throws Exception {
        // 在主目录下启动时 ./.paicli/mcp.json 就是 ~/.paicli/mcp.json
        Path user = tempDir.resolve("mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["fs"]}}}
                """);

        Map<String, McpServerConfig> configs = new McpConfigLoader(user, user, tempDir).load();

        assertFalse(configs.get("fs").projectScoped());
    }

    @Test
    void canonicalPayloadIncludesDisabledAndSortsMaps() {
        McpServerConfig config = new McpServerConfig();
        config.setCommand("node");
        config.setEnv(new java.util.LinkedHashMap<>(Map.of("B", "2", "A", "1")));
        byte[] original = McpConfigLoader.fingerprintPayload(config);
        config.setEnv(new java.util.TreeMap<>(config.getEnv()));
        assertArrayEquals(original, McpConfigLoader.fingerprintPayload(config));
        config.setDisabled(true);
        assertFalse(java.util.Arrays.equals(original, McpConfigLoader.fingerprintPayload(config)));
    }

    @Test
    void prepareRestoresRawPlaceholdersAndReappliesOverride(@TempDir Path temp) throws Exception {
        String variable = "PAICLI_C2A_TEST_VALUE";
        String previous = System.getProperty(variable);
        McpConfigLoader loader = new McpConfigLoader(temp.resolve("user.json"), temp.resolve("project.json"), temp);
        McpTrustStore store = new McpTrustStore(temp.resolve("state/trust.json"));
        McpServerConfig config = new McpServerConfig();
        config.setCommand("node");
        config.setArgs(List.of("${" + variable + "}"));
        config.markUserScoped(null);
        byte[] source = config.sourcePayload();
        try {
            System.setProperty(variable, "first");
            loader.prepare(config);
            String first = store.fingerprint(McpConfigLoader.fingerprintPayload(config), true);
            System.setProperty(variable, "second");
            loader.prepare(config);
            assertEquals(List.of("second"), config.getArgs());
            assertNotEquals(first, store.fingerprint(McpConfigLoader.fingerprintPayload(config), false));
            assertArrayEquals(source, config.sourcePayload());
            config.overrideArgs(List.of("override"));
            loader.prepare(config);
            assertEquals(List.of("override"), config.getArgs());
            config.overrideArgs(List.of("${" + variable + "}"));
            assertArrayEquals(source, config.sourcePayload());
            config.sourceConfig().setCommand("mutated");
            loader.prepare(config);
            assertEquals("node", config.getCommand());
        } finally {
            if (previous == null) System.clearProperty(variable);
            else System.setProperty(variable, previous);
        }
    }

    @Test
    void preparePreservesRuntimeEnableOverrideForDisabledSource(@TempDir Path temp) throws Exception {
        Path user = temp.resolve("user.json");
        Files.writeString(user, """
                {"mcpServers":{"personal":{"command":"node","disabled":true}}}
                """);
        McpConfigLoader loader = new McpConfigLoader(user, temp.resolve("project.json"), temp);
        McpServerConfig config = loader.load().get("personal");
        assertTrue(config.isDisabled());

        config.setDisabled(false);
        loader.prepare(config);

        assertFalse(config.isDisabled(), "prepare must not undo an explicit runtime enable");
        loader.prepare(config);
        assertFalse(config.isDisabled(), "repeated prepare must preserve runtime enable");
    }
}
