package com.paicli.mcp.oauth;

import com.paicli.mcp.transport.StreamableHttpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OAuthTransportTest {
    @Test void unauthorizedRefreshesAndRetriesExactlyOnceEvenIfSecond401() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().setResponseCode(401));server.enqueue(new MockResponse().setResponseCode(401));
            AtomicInteger refresh=new AtomicInteger();
            McpTokenProvider provider=new McpTokenProvider(){public String accessToken(){return "old";}public String refreshAfterUnauthorized(String rejected){assertEquals("old",rejected);refresh.incrementAndGet();return "new";}};
            var transport=new StreamableHttpTransport(server.url("/mcp").toString(),Map.of("X-Static","kept"),provider);
            assertThrows(IOException.class,()->transport.send(new ObjectMapper().readTree("{}")));
            assertEquals(1,refresh.get());assertEquals(2,server.getRequestCount());
            assertEquals("Bearer old",server.takeRequest().getHeader("Authorization"));
            var retry=server.takeRequest();assertEquals("Bearer new",retry.getHeader("Authorization"));assertEquals("kept",retry.getHeader("X-Static"));
        }
    }
    @Test void refreshSuccessDeliversAndCloseUsesCurrentTokenWithoutRefresh() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setHeader("Mcp-Session-Id","session").setBody("{\"result\":{}}"));server.enqueue(new MockResponse().setResponseCode(204));
            AtomicInteger calls=new AtomicInteger();AtomicInteger refresh=new AtomicInteger();
            McpTokenProvider provider=new McpTokenProvider(){public String accessToken(){calls.incrementAndGet();return "old";}public String refreshAfterUnauthorized(String token){refresh.incrementAndGet();return "new";}public String currentToken(){return "new";}};
            var transport=new StreamableHttpTransport(server.url("/mcp").toString(),Map.of(),provider);
            AtomicInteger delivered=new AtomicInteger();transport.onReceive(n->delivered.incrementAndGet());
            transport.send(new ObjectMapper().readTree("{}"));transport.close();
            assertEquals(1,calls.get());assertEquals(1,refresh.get());assertEquals(1,delivered.get());
            server.takeRequest();server.takeRequest();var delete=server.takeRequest();assertEquals("DELETE",delete.getMethod());assertEquals("Bearer new",delete.getHeader("Authorization"));
        }
    }
    @Test void forbiddenAndRedirectNeverRefreshOrLeak() throws Exception {
        try(var server=new MockWebServer();var other=new MockWebServer()) {
            server.start();other.start();AtomicInteger refresh=new AtomicInteger();
            McpTokenProvider provider=new McpTokenProvider(){public String accessToken(){return "secret";}public String refreshAfterUnauthorized(String token){refresh.incrementAndGet();return "new";}};
            for(int status:new int[]{403,302,307}) {
                server.enqueue(new MockResponse().setResponseCode(status).addHeader("Location",other.url("/steal")).addHeader("WWW-Authenticate","Bearer error=\"insufficient_scope\""));
                var transport=new StreamableHttpTransport(server.url("/mcp").toString(),Map.of(),provider);
                assertThrows(IOException.class,()->transport.send(new ObjectMapper().readTree("{}")));
            }
            assertEquals(0,refresh.get());assertEquals(0,other.getRequestCount());assertEquals(3,server.getRequestCount());
        }
    }
    @Test void nonOauthConstructorPreservesNormalRedirectWithStaticHeaders() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", server.url("/redirected")));
            server.enqueue(new MockResponse().setBody("{}"));
            var transport = new StreamableHttpTransport(server.url("/mcp").toString(), Map.of("Authorization", "Bearer static"));
            transport.send(new ObjectMapper().readTree("{}"));
            assertEquals(2, server.getRequestCount());
            assertEquals("/mcp", server.takeRequest().getPath());
            var redirected=server.takeRequest();assertEquals("/redirected", redirected.getPath());
            assertEquals("Bearer static", redirected.getHeader("Authorization"));
        }
    }
    @Test void resourceMismatchStopsRequestBeforeAnyTokenCanLeak() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var provider = new OAuthTokenProvider(new OAuthTokenStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "private", "tokens.json")), "https://different.example/mcp", "https://as.example", "client");
            var transport = new StreamableHttpTransport(server.url("/mcp").toString(), Map.of(), provider);
            assertThrows(IOException.class, () -> transport.send(new ObjectMapper().readTree("{}")));
            assertEquals(0, server.getRequestCount());
        }
    }
    @Test void staticAuthorizationWithOauthRejected() {
        var tokens=new McpTokenProvider(){public String accessToken(){return "secret";}public String refreshAfterUnauthorized(String t){return "secret";}};
        assertThrows(IllegalArgumentException.class,()->new StreamableHttpTransport("https://mcp.example",Map.of("aUtHoRiZaTiOn","value"),tokens));
    }
}
