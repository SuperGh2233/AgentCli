package com.paicli.mcp.oauth;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OAuthFlowTest {
    @TempDir Path temp;
    MockWebServer server;
    String resource,issuer;
    OAuthTokenStore store;
    @BeforeEach void setup() throws Exception {
        server=new MockWebServer();server.start();resource=server.url("/mcp").toString();issuer=server.url("/as").toString();
        store=new OAuthTokenStore(temp.resolve("private/tokens.json"));
    }
    @AfterEach void close() throws Exception {server.close();}
    void discovery(String scopes, String methods) { discovery(scopes, methods, null); }
    void discovery(String scopes, String methods, String prmScopes) {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Bearer "+scopes));
        server.enqueue(new MockResponse().setBody("{\"resource\":\""+resource+"\",\"authorization_servers\":[\""+issuer+"\"]"+(prmScopes==null?"":",\"scopes_supported\":"+prmScopes)+"}"));
        server.enqueue(new MockResponse().setBody("{\"issuer\":\""+issuer+"\",\"authorization_endpoint\":\""+server.url("/authorize")+"\",\"token_endpoint\":\""+server.url("/token")+"\",\"response_types_supported\":[\"code\"],\"grant_types_supported\":[\"authorization_code\"],\"token_endpoint_auth_methods_supported\":[\"none\"],\"authorization_response_iss_parameter_supported\":true,\"code_challenge_methods_supported\":"+methods+"}"));
    }
    String token(String access,String refresh) {return "{\"token_type\":\"Bearer\",\"access_token\":\""+access+"\",\"refresh_token\":\""+refresh+"\",\"expires_in\":3600}";}
    @Test void endToEndResourcePkceStateIssuerAndBrowserFallback() throws Exception {
        discovery("scope=\"read\"","[\"S256\"]");server.enqueue(new MockResponse().setBody(token("access","refresh")));
        AtomicReference<HttpUrl> auth=new AtomicReference<>();AtomicBoolean closed=new AtomicBoolean();List<String> output=new ArrayList<>();
        OAuthFlow.Callback callback=new OAuthFlow.Callback(){
            public String redirectUri(){return "http://127.0.0.1:4567/oauth/callback";}
            public Map<String,String> await(Duration timeout){return Map.of("state",auth.get().queryParameter("state"),"code","auth-code","iss",issuer);}
            public void close(){closed.set(true);}
        };
        var flow=new OAuthFlow(store,new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)),uri->{auth.set(HttpUrl.get(uri));return false;},()->callback);
        flow.login(resource,"client",List.of("read","other"),output::add);
        HttpUrl url=auth.get();assertEquals(resource,url.queryParameter("resource"));assertEquals("read",url.queryParameter("scope"));
        assertEquals("S256",url.queryParameter("code_challenge_method"));assertEquals(43,url.queryParameter("state").length());
        server.takeRequest();server.takeRequest();server.takeRequest();var request=server.takeRequest();
        Map<String,String> form=LoopbackCallback.parameters(request.getBody().readUtf8());
        assertEquals(resource,form.get("resource"));assertEquals("authorization_code",form.get("grant_type"));
        assertEquals(url.queryParameter("code_challenge"),OAuthFlow.challenge(form.get("code_verifier")));
        assertEquals(callback.redirectUri(),form.get("redirect_uri"));assertEquals("client",form.get("client_id"));
        assertNull(request.getHeader("Authorization"));assertTrue(closed.get());
        assertNotNull(store.find(resource,issuer,"client"));
        assertTrue(output.get(0).contains("http"));assertFalse(output.toString().contains("code_verifier"));assertFalse(output.toString().contains("access"));
    }
    @Test void scopeConfigCannotOverrideChallengeAndFailsBeforeBrowser() {
        discovery("scope=\"write\"","[\"S256\"]");
        AtomicInteger opened=new AtomicInteger();
        var flow=new OAuthFlow(store,new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)),u->{opened.incrementAndGet();return true;},()->new OAuthFlow.Callback(){
            public String redirectUri(){return "http://127.0.0.1:1/oauth/callback";}
            public Map<String,String> await(Duration d){fail();return Map.of();}public void close(){}
        });
        assertThrows(IOException.class,()->flow.login(resource,"client",List.of("read"),s->{}));assertEquals(0,opened.get());assertEquals(3,server.getRequestCount());
    }
    @Test void scopePolicyChallengeAuthoritativeMetadataIntersectionAndAbsentOmission() throws Exception {
        var challenged = new OAuthDiscovery.Metadata(resource,issuer,"authorize","token",false,List.of("read","write"),true);
        assertThrows(IOException.class,()->OAuthFlow.selectScopes(challenged,List.of("read")));
        assertEquals(List.of("read","write"),OAuthFlow.selectScopes(challenged,List.of("read","write","extra")));
        var metadata = new OAuthDiscovery.Metadata(resource,issuer,"authorize","token",false,List.of("read","write"),false);
        assertEquals(List.of("read"),OAuthFlow.selectScopes(metadata,List.of("read")));
        assertEquals(List.of("read","write"),OAuthFlow.selectScopes(metadata,null));
        var absent = new OAuthDiscovery.Metadata(resource,issuer,"authorize","token",false,List.of(),false);
        assertTrue(OAuthFlow.selectScopes(absent,List.of("read")).isEmpty());
    }
    @Test void metadataAllowListIntersectionAndAbsentGuidanceReachAuthorizationUrl() throws Exception {
        for (boolean guidance : List.of(true,false)) {
            discovery("","[\"S256\"]",guidance ? "[\"read\",\"write\"]" : null);
            AtomicReference<HttpUrl> auth = new AtomicReference<>();
            var flow = new OAuthFlow(store,new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT,OAuthHttp::resourceUrl)),uri->{auth.set(HttpUrl.get(uri));return true;},()->new OAuthFlow.Callback(){
                public String redirectUri(){return "http://127.0.0.1:12345/oauth/callback";}
                public Map<String,String> await(Duration timeout) throws IOException {throw new IOException("test stops after authorization URL");}
                public void close(){}
            });
            assertThrows(IOException.class,()->flow.login(resource,"client",List.of("read"),s->{}));
            assertNotNull(auth.get());assertEquals(guidance ? "read" : null,auth.get().queryParameter("scope"));
        }
    }
    @Test void closeRefreshesExpiredTokenBeforeSessionDelete() throws Exception {
        store.save(new OAuthTokenStore.Token(resource,issuer,"client",server.url("/token").toString(),"old","refresh",0));
        var provider = new OAuthTokenProvider(store,resource,issuer,"client",new OAuthHttp(OAuthHttp.CLIENT,OAuthHttp::resourceUrl));
        var transport = new com.paicli.mcp.transport.StreamableHttpTransport(resource,Map.of(),provider);
        server.enqueue(new MockResponse().setHeader("Mcp-Session-Id","session").setBody("{}"));
        transport.send(OAuthHttp.JSON.createObjectNode());
        store.save(new OAuthTokenStore.Token(resource,issuer,"client",server.url("/token").toString(),"old","refresh",1));
        server.enqueue(new MockResponse().setBody(token("new","rotated")));server.enqueue(new MockResponse().setResponseCode(204));
        transport.close();server.takeRequest();
        var refresh=server.takeRequest();assertEquals("/token",refresh.getPath());
        var deletion=server.takeRequest();assertEquals("DELETE",deletion.getMethod());assertEquals("Bearer new",deletion.getHeader("Authorization"));
    }
    @Test void unsupportedPkceNeverOpensCallbackOrBrowser() {
        discovery("","[]");var flow=new OAuthFlow(store,new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)),u->{fail();return true;},()->{fail();return null;});
        assertThrows(IOException.class,()->flow.login(resource,"client",null,s->{}));
    }
    @Test void missingExpiryIsUnboundedButExplicitZeroExpiresImmediately() throws Exception {
        OAuthHttp http = new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl);
        FormBody form = new FormBody.Builder().add("grant_type", "authorization_code").build();
        String base = "{\"token_type\":\"Bearer\",\"access_token\":\"access\"";
        server.enqueue(new MockResponse().setBody(base + "}"));
        var withoutExpiry = OAuthFlow.exchange(resource, issuer, "client", server.url("/token").toString(), form,
                null, store, http);
        assertEquals(0, withoutExpiry.expiresAt());
        server.enqueue(new MockResponse().setBody(base + ",\"expires_in\":0}"));
        var immediatelyExpired = OAuthFlow.exchange(resource, issuer, "client", server.url("/token").toString(), form,
                null, store, http);
        assertTrue(immediatelyExpired.expiresAt() > 0);
        assertTrue(immediatelyExpired.expiresAt() <= System.currentTimeMillis());
    }

    @Test void stateAndIssuerMatrixIncludingErrors() throws Exception {
        assertEquals("code",OAuthFlow.validateResponse(Map.of("state","expected","code","code"),"expected",issuer,false));
        assertEquals("code",OAuthFlow.validateResponse(Map.of("state","expected","code","code","iss",issuer),"expected",issuer,true));
        for(var params:List.of(Map.of("state","wrong","code","code","iss",issuer),Map.of("state","expected","code","code"),Map.of("state","expected","code","code","iss",issuer+"/"),Map.of("state","expected","error","SECRET","iss",issuer+"/"),Map.of("state","expected","iss",issuer)))
            assertFalse(assertThrows(IOException.class,()->OAuthFlow.validateResponse(params,"expected",issuer,true)).getMessage().contains("SECRET"));
        assertThrows(IOException.class,()->OAuthFlow.validateResponse(Map.of("state","expected","code","code","iss",issuer+"/"),"expected",issuer,false));
        assertThrows(IOException.class,()->OAuthFlow.validateResponse(Map.of("state","expected","code","code","error","secret","iss",issuer),"expected",issuer,true));
    }
    @Test void wrongStateDoesNotExchangeCode() throws Exception {
        discovery("","[\"S256\"]");AtomicBoolean closed=new AtomicBoolean();
        var flow=new OAuthFlow(store,new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl)),u->true,()->new OAuthFlow.Callback(){
            public String redirectUri(){return "http://127.0.0.1:1/oauth/callback";}
            public Map<String,String> await(Duration d){return Map.of("state","wrong","code","secret","iss",issuer);}
            public void close(){closed.set(true);}
        });
        assertThrows(IOException.class,()->flow.login(resource,"client",null,s->{}));assertEquals(3,server.getRequestCount());assertTrue(closed.get());
    }
    @Test void refreshRotationBindsResourceAndPreservesOtherIssuer() throws Exception {
        store.save(new OAuthTokenStore.Token(resource,issuer,"client",server.url("/token").toString(),"old","refresh-old",0));
        store.save(new OAuthTokenStore.Token(resource,"https://other.example","client","https://other.example/token","other","other-refresh",0));
        server.enqueue(new MockResponse().setBody(token("new","refresh-new")));
        var provider=new OAuthTokenProvider(store,resource,issuer,"client",new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl));
        assertEquals("new",provider.refreshAfterUnauthorized("old"));
        var form=LoopbackCallback.parameters(server.takeRequest().getBody().readUtf8());
        assertEquals(resource,form.get("resource"));assertEquals("refresh-old",form.get("refresh_token"));assertEquals("client",form.get("client_id"));
        assertEquals("refresh-new",store.find(resource,issuer,"client").refreshToken());
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\",\"error_description\":\"SECRET\"}"));
        assertFalse(assertThrows(IOException.class,()->provider.refreshAfterUnauthorized("new")).getMessage().contains("SECRET"));
        assertNull(store.find(resource,issuer,"client"));assertNotNull(store.find(resource,"https://other.example","client"));
        assertThrows(IOException.class,()->provider.validateResource("https://different.example/mcp"));
    }
    @Test void expiryRefreshAndMissingRotatedRefreshPreservesPrevious() throws Exception {
        store.save(new OAuthTokenStore.Token(resource,issuer,"client",server.url("/token").toString(),"old","refresh",1));
        server.enqueue(new MockResponse().setBody("{\"access_token\":\"next\",\"token_type\":\"Bearer\"}"));
        var provider=new OAuthTokenProvider(store,resource,issuer,"client",new OAuthHttp(OAuthHttp.CLIENT, OAuthHttp::resourceUrl));assertEquals("next",provider.accessToken());
        assertEquals("refresh",store.find(resource,issuer,"client").refreshToken());
        provider.revoke();assertThrows(IOException.class,provider::accessToken);
    }
}
