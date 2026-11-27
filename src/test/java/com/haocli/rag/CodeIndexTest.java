package com.haocli.rag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CodeIndexTest {

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex();
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        assumeEmbeddingServiceAvailable();
        System.setProperty("haocli.rag.dir", "/tmp/haocli-test-rag-index");
        CodeIndex indexer = new CodeIndex();
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
    }

    @Test
    void reportsProgressThroughListener() {
        assumeEmbeddingServiceAvailable();
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(messages::add);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }

    /**
     * 这两个用例会真的调用 Embedding 服务。默认配置是本机 Ollama，CI 和没装 Ollama 的机器上连不上，
     * 这时跳过而不是报“没有索引到代码块”；配置了其他 Embedding 服务时照常运行。
     */
    private static void assumeEmbeddingServiceAvailable() {
        if (!"ollama".equalsIgnoreCase(new EmbeddingClient().getProvider())) {
            return;
        }
        String baseUrl = System.getenv("EMBEDDING_BASE_URL");
        URI uri = URI.create(baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl);
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), 500);
        } catch (IOException e) {
            assumeTrue(false, "本机没有可用的 Ollama Embedding 服务（" + uri + "），跳过");
        }
    }
}
