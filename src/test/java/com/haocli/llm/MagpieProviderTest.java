package com.haocli.llm;

import com.haocli.config.HaoCliConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** magpie：显式选择才使用；默认地址和模型；workbuddy/ 开头的模型归到 magpie。 */
class MagpieProviderTest {

    @Test
    void createsClientWithDefaultsWhenSelected() {
        HaoCliConfig config = new HaoCliConfig();
        config.getProviders().put("magpie", new HaoCliConfig.ProviderConfig("local", null, null));

        LlmClient client = LlmClientFactory.create("magpie", null, config);

        MagpieClient magpie = assertInstanceOf(MagpieClient.class, client);
        assertEquals("magpie", magpie.getProviderName());
        assertEquals(MagpieClient.DEFAULT_MODEL, magpie.getModelName());
        assertEquals("http://127.0.0.1:3425/v1/chat/completions", magpie.getApiUrl());
    }

    @Test
    void honoursConfiguredBaseUrlAndModel() {
        HaoCliConfig config = new HaoCliConfig();
        config.getProviders().put("magpie",
                new HaoCliConfig.ProviderConfig("local", "http://127.0.0.1:9999/v1/", "workbuddy/glm-5.3"));

        MagpieClient magpie = assertInstanceOf(MagpieClient.class, LlmClientFactory.create("magpie", null, config));

        assertEquals("workbuddy/glm-5.3", magpie.getModelName());
        assertEquals("http://127.0.0.1:9999/v1/chat/completions", magpie.getApiUrl());
    }

    @Test
    void isNeverPickedAsAnAutomaticFallback() {
        HaoCliConfig config = new HaoCliConfig();
        config.getProviders().put("magpie", new HaoCliConfig.ProviderConfig("local", null, null));

        LlmClient client = LlmClientFactory.createFromConfig(config);

        assertFalse(client instanceof MagpieClient, "magpie must only be used when chosen explicitly");
    }

}
