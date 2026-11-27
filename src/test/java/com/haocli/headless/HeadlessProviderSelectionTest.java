package com.haocli.headless;

import com.haocli.config.HaoCliConfig;
import com.haocli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** HAOCLI_PROVIDER：评测容器里显式走 magpie；指定的服务商不可用时不悄悄换别的。 */
class HeadlessProviderSelectionTest {

    private static HaoCliConfig configWith(String provider, String apiKey, String baseUrl, String model) {
        HaoCliConfig config = new HaoCliConfig();
        Map<String, HaoCliConfig.ProviderConfig> providers = new HashMap<>(config.getProviders());
        providers.put(provider, new HaoCliConfig.ProviderConfig(apiKey, baseUrl, model));
        config.setProviders(providers);
        return config;
    }

    @Test
    void forcedMagpieUsesTheGatewayModel() {
        HaoCliConfig config = configWith("magpie", "local", "http://host.docker.internal:3425/v1",
                "workbuddy/deepseek-v4.1-flash");

        LlmClient client = HeadlessCommand.selectClient("magpie", config);

        assertEquals("magpie", client.getProviderName());
        assertEquals("workbuddy/deepseek-v4.1-flash", client.getModelName());
    }

    @Test
    void forcedProviderNeverFallsBackToAnotherOne() {
        HaoCliConfig config = configWith("deepseek", "sk-test", null, "deepseek-flash");

        assertNull(HeadlessCommand.selectClient("no-such-provider", config),
                "指定的服务商不可用时返回 null，由调用方报错，不能悄悄改走 DeepSeek");
    }
}
