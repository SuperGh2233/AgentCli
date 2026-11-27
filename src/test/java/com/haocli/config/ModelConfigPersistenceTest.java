package com.haocli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haocli.llm.ModelProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ModelConfigPersistenceTest {
    @TempDir Path temp;
    @Test
    void atomicSaveRoundTripsModelProfilesAndLeavesNoTemporaryFile() throws Exception {
        Path file = temp.resolve("config.json");
        HaoCliConfig config = new HaoCliConfig();
        var provider = new HaoCliConfig.ProviderConfig("synthetic-key", null, "next");
        provider.getModels().put("next", new ModelProfile("deepseek-flash", 200000, false, null, null, true, true, 8192, "manual"));
        config.getProviders().put("deepseek", provider);
        config.saveOrThrow(file);
        config.saveOrThrow(file);
        HaoCliConfig loaded = new ObjectMapper().readValue(file.toFile(), HaoCliConfig.class);
        assertEquals(provider.getModels(), loaded.getProviders().get("deepseek").getModels());
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }
}
