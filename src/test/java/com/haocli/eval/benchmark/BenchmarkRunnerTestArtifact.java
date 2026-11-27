package com.haocli.eval.benchmark;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Synthetic policy-valid runner artifact for coordinator/worker boundary tests. */
final class BenchmarkRunnerTestArtifact {
    private static final List<String> REQUIRED_CLASSES = List.of(
            "com/haocli/eval/benchmark/BenchmarkRelayWorkerMain.class",
            "com/haocli/eval/benchmark/BenchmarkToolRegistry.class",
            "com/haocli/eval/benchmark/BenchmarkToolProfile.class",
            "com/haocli/eval/benchmark/relay/BenchmarkFramedChannel.class",
            "com/haocli/eval/benchmark/relay/BenchmarkProviderRelay.class",
            "com/haocli/eval/benchmark/relay/BenchmarkRelayProtocol.class",
            "com/haocli/eval/benchmark/relay/RelayLlmClient.class",
            "com/haocli/eval/benchmark/relay/RelayMcpTransport.class",
            "com/haocli/eval/benchmark/relay/RelayWebDependencies.class",
            "com/haocli/eval/benchmark/relay/RelayHitlHandler.class",
            "com/haocli/eval/benchmark/relay/ScriptedInteraction.class",
            "com/haocli/eval/benchmark/relay/RelayWireConversions.class");

    private BenchmarkRunnerTestArtifact() {
    }

    static Path create(Path path) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(
                Attributes.Name.MAIN_CLASS, BenchmarkRunnerArtifactPolicy.MAIN_CLASS);
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output, manifest)) {
            int marker = 1;
            for (String name : REQUIRED_CLASSES) {
                jar.putNextEntry(new JarEntry(name));
                jar.write(new byte[]{(byte) marker++});
                jar.closeEntry();
            }
        }
        return path.toAbsolutePath();
    }
}
