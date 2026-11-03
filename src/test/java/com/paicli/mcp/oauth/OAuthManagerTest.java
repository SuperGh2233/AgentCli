package com.paicli.mcp.oauth;

import com.paicli.mcp.*;
import com.paicli.mcp.config.*;
import com.paicli.mcp.transport.McpPidFile;
import com.paicli.tool.ToolRegistry;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OAuthManagerTest {
    @TempDir Path temp;
    MockWebServer web;
    OAuthTokenStore store;
    McpServerConfig config;
    String resource,issuer;
    AtomicReference<HttpUrl> authorization=new AtomicReference<>();
    AtomicBoolean invalidState=new AtomicBoolean();
    AtomicInteger browsers=new AtomicInteger(), deletes=new AtomicInteger();
    AtomicReference<String> deleteAuthorization=new AtomicReference<>();
    AtomicBoolean credentialsPresentAtDelete=new AtomicBoolean();
    OAuthDiscovery discovery=new OAuthDiscovery(new OAuthHttp(OAuthHttp.CLIENT,OAuthHttp::resourceUrl));
    @BeforeEach void setup() throws Exception {
        web=new MockWebServer();web.start();resource=web.url("/mcp").toString();issuer=web.url("/as").toString();
        store=new OAuthTokenStore(temp.resolve("private/tokens.json"));config=new McpServerConfig();config.setUrl(resource);
        var oauth=new McpOAuthConfig();oauth.setClientId("client");config.setOauth(oauth);
        web.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r) {
            try {
                if(r.getPath().equals("/mcp")) {
                    if(r.getMethod().equals("DELETE")) {
                        deletes.incrementAndGet();deleteAuthorization.set(r.getHeader("Authorization"));
                        credentialsPresentAtDelete.set(store.find(resource,issuer,"client")!=null);return new MockResponse().setResponseCode(204);
                    }
                    if(r.getHeader("Authorization")==null)return new MockResponse().setResponseCode(401);
                    var body=OAuthHttp.JSON.readTree(r.getBody().readUtf8());
                    if(!body.has("id"))return new MockResponse().setResponseCode(202);
                    var result=OAuthHttp.JSON.createObjectNode().put("jsonrpc","2.0");result.set("id",body.get("id"));
                    var payload=result.putObject("result");
                    if(body.path("method").asText().equals("initialize")) {payload.putObject("capabilities");payload.put("protocolVersion","2025-03-26");}
                    else payload.putArray("tools");
                    return new MockResponse().setHeader("Mcp-Session-Id","session").setBody(result.toString());
                }
                if(r.getPath().contains("oauth-protected-resource"))return new MockResponse().setBody("{\"resource\":\""+resource+"\",\"authorization_servers\":[\""+issuer+"\"]}");
                if(r.getPath().contains("oauth-authorization-server"))return new MockResponse().setBody("{\"issuer\":\""+issuer+"\",\"authorization_endpoint\":\""+web.url("/authorize")+"\",\"token_endpoint\":\""+web.url("/token")+"\",\"response_types_supported\":[\"code\"],\"grant_types_supported\":[\"authorization_code\"],\"token_endpoint_auth_methods_supported\":[\"none\"],\"code_challenge_methods_supported\":[\"S256\"]}");
                if(r.getPath().equals("/token"))return new MockResponse().setBody("{\"token_type\":\"Bearer\",\"access_token\":\"access\",\"refresh_token\":\"refresh\"}");
                return new MockResponse().setResponseCode(404);
            } catch(Exception e){return new MockResponse().setResponseCode(500);}
        }});
    }
    @AfterEach void close() throws Exception {web.close();}
    McpServerManager manager() throws IOException {
        var loader=new McpConfigLoader(temp.resolve("user.json"),temp.resolve("project.json"),temp){@Override public Map<String,McpServerConfig> load(){return Map.of("remote",config);}};
        var manager=new McpServerManager(new ToolRegistry(),temp,loader,new McpTrustStore(temp.resolve("trust.json")),new McpPidFile(temp.resolve("pids.json"))){
            @Override protected OAuthTokenStore oauthTokenStore(){return store;}
            @Override protected OAuthDiscovery oauthDiscovery(){return discovery;}
            @Override protected OAuthFlow oauthFlow(OAuthTokenStore s){return new OAuthFlow(s,discovery,uri->{browsers.incrementAndGet();authorization.set(HttpUrl.get(uri));return true;},()->new OAuthFlow.Callback(){
                public String redirectUri(){return "http://127.0.0.1:12345/oauth/callback";}
                public Map<String,String> await(Duration timeout){return Map.of("state",invalidState.get()?"bad":authorization.get().queryParameter("state"),"code","code");}
                public void close(){}
            });}
        };
        manager.loadConfiguredServers();return manager;
    }
    @Test void loginLogoutClosesSessionBeforeRevocationAndReloginEnablesOnlyOnSuccess() throws Exception {
        try(var manager=manager()) {
            config.setDisabled(true);
            assertTrue(manager.login("remote",s->{}).contains("登录成功"));assertEquals(McpServerStatus.READY,manager.server("remote").status());assertFalse(config.isDisabled());
            assertTrue(manager.logout("remote").contains("已注销"));assertEquals(1,deletes.get());assertEquals("Bearer access",deleteAuthorization.get());assertTrue(credentialsPresentAtDelete.get());
            assertTrue(config.isDisabled());assertNull(manager.server("remote").client());assertNull(store.find(resource,issuer,"client"));
            int count=web.getRequestCount();assertEquals(McpServerStatus.DISABLED,manager.server("remote").status());assertEquals(count,web.getRequestCount());
            invalidState.set(true);assertTrue(manager.login("remote",s->{}).contains("失败"));assertTrue(config.isDisabled());assertEquals(McpServerStatus.DISABLED,manager.server("remote").status());
            invalidState.set(false);assertTrue(manager.login("remote",s->{}).contains("登录成功"));assertFalse(config.isDisabled());assertEquals(McpServerStatus.READY,manager.server("remote").status());
        }
    }
    @Test void untrustedAndStdioNeverLaunchBrowserOrNetwork() throws Exception {
        config.markProjectScoped(temp.resolve("project.json"),"fingerprint");
        try(var manager=manager()) {assertTrue(manager.login("remote",s->fail()).contains("还没确认"));assertTrue(manager.logout("remote").contains("还没确认"));}
        config=new McpServerConfig();config.setCommand("node");
        try(var manager=manager()) {assertTrue(manager.login("remote",s->fail()).contains("仅支持"));}
        assertEquals(0,browsers.get());assertEquals(0,web.getRequestCount());
    }
}
