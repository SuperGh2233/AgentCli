package com.paicli.mcp.oauth;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OAuthDiscoveryTest {
    MockWebServer server;
    String resource, issuer;
    @BeforeEach void setup() throws Exception {
        server = new MockWebServer(); server.start();
        resource = server.url("/public/mcp").toString(); issuer = server.url("/tenant").toString();
    }
    @AfterEach void close() throws Exception { server.close(); }
    String prm() { return "{\"resource\":\""+resource+"\",\"authorization_servers\":[\""+issuer+"\"],\"scopes_supported\":[\"metadata\"]}"; }
    String as(String pkce) { return "{\"issuer\":\""+issuer+"\",\"authorization_endpoint\":\""+server.url("/authorize")+"\",\"token_endpoint\":\""+server.url("/token")+"\",\"response_types_supported\":[\"code\"],\"grant_types_supported\":[\"authorization_code\"],\"token_endpoint_auth_methods_supported\":[\"none\"],\"code_challenge_methods_supported\":"+pkce+"}"; }
    void enqueuePrmAndAs() { server.enqueue(new MockResponse().setBody(prm())); server.enqueue(new MockResponse().setBody(as("[\"S256\"]"))); }
    @Test void postInitializeFindsChallengeWhenGetIs405AndDoesNotLeakHeaders() throws Exception {
        List<RecordedRequest> requests = new ArrayList<>();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                requests.add(request);
                if (request.getPath().equals("/public/mcp")) {
                    if (request.getMethod().equals("GET")) return new MockResponse().setResponseCode(405);
                    return new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"a,b\", Bearer resource_metadata=\""+server.url("/metadata")+"\", scope=\"read write\"");
                }
                if (request.getPath().equals("/metadata")) return new MockResponse().setBody(prm());
                return new MockResponse().setBody(as("[\"S256\"]"));
            }
        });
        var metadata = new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource);
        assertEquals(List.of("read", "write"), metadata.scopes());
        assertEquals("POST", requests.get(0).getMethod());
        var initialize = OAuthHttp.JSON.readTree(requests.get(0).getBody().readUtf8());
        assertEquals("initialize", initialize.path("method").asText());
        assertEquals("2025-03-26", initialize.at("/params/protocolVersion").asText());
        assertEquals("/metadata", requests.get(1).getPath());
        for (var r : requests) assertNull(r.getHeader("Authorization"));
        assertNull(requests.get(1).getHeader("MCP-Protocol-Version"));
    }
    @Test void modernOauthProbeUsesDiscoverAndNeverInitialize() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)); enqueuePrmAndAs();
        var discovery = new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl));
        assertEquals(resource, discovery.discover(resource, com.paicli.mcp.protocol.McpProtocolVersion.MODERN).resource());
        var probe = server.takeRequest();
        assertEquals("POST", probe.getMethod());
        assertEquals("2026-07-28", probe.getHeader("MCP-Protocol-Version"));
        assertEquals("server/discover", probe.getHeader("Mcp-Method"));
        assertNull(probe.getHeader("Mcp-Session-Id")); assertNull(probe.getHeader("Authorization"));
        var body = OAuthHttp.JSON.readTree(probe.getBody().readUtf8());
        assertEquals("server/discover", body.path("method").asText());
        assertFalse(body.path("params").has("protocolVersion"));
        var meta = body.path("params").path("_meta");
        assertEquals("2026-07-28", meta.path("io.modelcontextprotocol/protocolVersion").asText());
        assertTrue(meta.path("io.modelcontextprotocol/clientCapabilities").isObject());
        assertEquals("paicli", meta.path("io.modelcontextprotocol/clientInfo").path("name").asText());
        for (int i = 0; i < 2; i++) {
            var metadata = server.takeRequest();
            assertEquals("GET", metadata.getMethod());
            assertNull(metadata.getHeader("Mcp-Method"));
            assertNull(metadata.getHeader("MCP-Protocol-Version"));
            assertNull(metadata.getHeader("Authorization"));
        }
        assertEquals(3, server.getRequestCount());
    }

    @Test void pathResourceFallsBackToRoot() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(404)); enqueuePrmAndAs();
        assertEquals(resource, new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource).resource());
        assertEquals("/public/mcp", server.takeRequest().getPath());
        assertEquals("/.well-known/oauth-protected-resource/public/mcp", server.takeRequest().getPath());
        assertEquals("/.well-known/oauth-protected-resource", server.takeRequest().getPath());
    }
    @Test void rfc8414AndBothOidcPathCandidatesInOrder() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setResponseCode(404)); server.enqueue(new MockResponse().setBody(as("[\"S256\"]")));
        new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).authorizationMetadata(issuer);
        assertEquals("/.well-known/oauth-authorization-server/tenant", server.takeRequest().getPath());
        assertEquals("/.well-known/openid-configuration/tenant", server.takeRequest().getPath());
        assertEquals("/tenant/.well-known/openid-configuration", server.takeRequest().getPath());
    }
    @Test void rootIssuerOidcFallback() throws Exception {
        issuer = server.url("/").toString().replaceAll("/$", "");
        server.enqueue(new MockResponse().setResponseCode(404));server.enqueue(new MockResponse().setBody(as("[\"S256\"]")));
        new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).authorizationMetadata(issuer);
        assertEquals("/.well-known/oauth-authorization-server", server.takeRequest().getPath());
        assertEquals("/.well-known/openid-configuration", server.takeRequest().getPath());
    }
    @Test void issuerMismatchFailsWithoutFallback() throws Exception {
        server.enqueue(new MockResponse().setBody(as("[\"S256\"]").replace(issuer, issuer+"/")));
        assertThrows(IOException.class, () -> new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).authorizationMetadata(issuer));
        assertEquals(1, server.getRequestCount());
    }
    @Test void missingOrPlainPkceFailsClosed() throws Exception {
        for (String methods : List.of("[]", "[\"plain\"]")) {
            server.enqueue(new MockResponse().setResponseCode(401));server.enqueue(new MockResponse().setBody(prm()));
            server.enqueue(new MockResponse().setBody(as(methods)));
            assertTrue(assertThrows(IOException.class, () -> new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource)).getMessage().contains("S256"));
        }
    }
    @Test void requiresAuthorizationCodeResponseAndGrant() throws Exception {
        List<String> unsupported = List.of(
                as("[\"S256\"]").replace("[\"code\"]", "[\"token\"]"),
                as("[\"S256\"]").replace("[\"authorization_code\"]", "[\"client_credentials\"]"));
        List<String> expectedMessages = List.of("response_type=code", "authorization_code");
        for (int i = 0; i < unsupported.size(); i++) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setBody(prm()));
            server.enqueue(new MockResponse().setBody(unsupported.get(i)));
            assertTrue(assertThrows(IOException.class,
                    () -> new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource))
                    .getMessage().contains(expectedMessages.get(i)));
        }
        // grant_types_supported is optional in RFC 8414; when omitted, the registered defaults apply.
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setBody(prm()));
        server.enqueue(new MockResponse().setBody(as("[\"S256\"]").replace(",\"grant_types_supported\":[\"authorization_code\"]", "")));
        assertDoesNotThrow(() -> new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource));
        assertEquals(9, server.getRequestCount());
    }

    @Test void publicClientRequiresExplicitNoneTokenEndpointAuthMethod() throws Exception {
        List<String> unsupported = List.of(
                as("[\"S256\"]").replace(",\"token_endpoint_auth_methods_supported\":[\"none\"]", ""),
                as("[\"S256\"]").replace("[\"none\"]", "[\"client_secret_basic\"]"));
        for (String metadata : unsupported) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setBody(prm()));
            server.enqueue(new MockResponse().setBody(metadata));
            assertTrue(assertThrows(IOException.class,
                    () -> new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource))
                    .getMessage().contains("none"));
        }
        assertEquals(6, server.getRequestCount());
    }

    @Test void metadataServerFailuresDoNotFallBack() throws Exception {
        for (int status : List.of(429,500,503,302)) {
            server.enqueue(new MockResponse().setResponseCode(status));
            assertThrows(IOException.class, () -> new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).authorizationMetadata(issuer));
        }
        assertEquals(4, server.getRequestCount());
    }
    @Test void resourceMismatchFails() {
        server.enqueue(new MockResponse().setResponseCode(401));server.enqueue(new MockResponse().setBody(prm().replace(resource,resource+"/other")));
        assertThrows(IOException.class, () -> new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource));
    }
    @Test void networkFailureDoesNotTryNextCandidate() {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet(); throw new IOException("sensitive remote error");
        }).retryOnConnectionFailure(false).build();
        var discovery = new OAuthDiscovery(new OAuthHttp(client, OAuthHttp::resourceUrl));
        assertFalse(assertThrows(IOException.class, () -> discovery.authorizationMetadata(issuer)).getMessage().contains("sensitive"));
        assertEquals(1, requests.get());
    }
    @Test void missingPkceAdvertisementFailsClosed() {
        server.enqueue(new MockResponse().setResponseCode(401));server.enqueue(new MockResponse().setBody(prm()));
        server.enqueue(new MockResponse().setBody(as("[]").replace(",\"code_challenge_methods_supported\":[]", "")));
        assertTrue(assertThrows(IOException.class, () -> new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource)).getMessage().contains("S256"));
    }
    @Test void metadataScopesFallbackAndAbsentScopes() throws Exception {
        for (boolean present : List.of(true, false)) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setBody(present ? prm() : prm().replace(",\"scopes_supported\":[\"metadata\"]", "")));
            server.enqueue(new MockResponse().setBody(as("[\"S256\"]")));
            var metadata = new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource);
            assertEquals(present ? List.of("metadata") : List.of(), metadata.scopes());
        }
    }
    @Test void productionRejectsHttpAuthorizationServers() throws Exception {
        assertThrows(IOException.class, () -> new OAuthDiscovery().authorizationMetadata(issuer));
        assertThrows(IOException.class, () -> OAuthHttp.httpsUrl(server.url("/token").toString()));
        assertThrows(IOException.class, () -> OAuthHttp.httpsUrl(server.url("/authorize").toString()));
        String loopback = server.url("/issuer").newBuilder().host("127.0.0.1").build().toString();
        assertThrows(IOException.class, () -> OAuthHttp.httpsUrl(loopback));
        assertEquals(0, server.getRequestCount());
        assertNotNull(OAuthHttp.resourceUrl(server.url("/mcp").newBuilder().host("127.0.0.1").build().toString()));
    }
    @Test void metadataDifferentOriginReceivesNoToken() throws Exception {
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Bearer resource_metadata=\""+other.url("/prm")+"\""));
            other.enqueue(new MockResponse().setBody(prm()));server.enqueue(new MockResponse().setBody(as("[\"S256\"]")));
            new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)).discover(resource);
            var r=other.takeRequest();assertNull(r.getHeader("Authorization"));assertNull(r.getHeader("MCP-Protocol-Version"));
        }
    }
}
