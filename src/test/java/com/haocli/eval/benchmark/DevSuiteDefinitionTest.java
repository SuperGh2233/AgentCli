package com.haocli.eval.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DevSuiteDefinitionTest {
    @Test
    void repositoryDevelopmentSuiteLoadsWithRunnableFixturesAndValidators() throws Exception {
        Path suitePath = Path.of("benchmarks", "haocli-native-agentbench-v0.1", "dev-suite.json")
                .toAbsolutePath().normalize();
        // benchmarks/ 目录目前没有纳入 git，新克隆的仓库里没有它，这时跳过而不是报错
        assumeTrue(Files.isRegularFile(suitePath), "本地没有 benchmarks/ 评测数据目录，跳过: " + suitePath);
        SuiteDefinition suite = SuiteDefinition.load(suitePath);

        assertEquals(8, suite.activeCases().size());
        assertEquals(100, suite.activeCases().stream().mapToInt(CaseDefinition::weight).sum());
        for (CaseDefinition definition : suite.activeCases()) {
            assertEquals(CaseDefinition.Mode.REACT, definition.mode());
            assertEquals(CaseDefinition.VerifierType.COMMAND, definition.verifierType());
            assertTrue(Files.isDirectory(suite.resolveFixture(definition)));
            assertTrue(suite.resolveVerifier(definition).arguments().contains("{workspace}"));
        }
    }
}
