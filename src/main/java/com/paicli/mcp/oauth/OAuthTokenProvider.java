package com.paicli.mcp.oauth;

import okhttp3.FormBody;
import java.io.IOException;

/** 每个资源/issuer/client 独立；不会因 401 启动浏览器。 */
public final class OAuthTokenProvider implements McpTokenProvider {
    private final OAuthTokenStore store;
    private final String resource;
    private final String issuer;
    private final String clientId;
    private boolean revoked;
    private final OAuthHttp http;

    public OAuthTokenProvider(OAuthTokenStore store, String resource, String issuer, String clientId) {
        this(store, resource, issuer, clientId, new OAuthHttp());
    }
    OAuthTokenProvider(OAuthTokenStore store, String resource, String issuer, String clientId, OAuthHttp http) {
        this.http = http;
        this.store = store; this.resource = resource; this.issuer = issuer; this.clientId = clientId;
    }
    @Override public void validateResource(String url) throws IOException {
        if (!resource.equals(OAuthHttp.resource(url))) throw new IOException("OAuth token 与 MCP resource 不匹配");
    }
    @Override public synchronized String accessToken() throws IOException {
        var token = token();
        if (token.expiresAt() != 0 && token.expiresAt() <= System.currentTimeMillis()) token = refresh(token);
        return token.accessToken();
    }
    @Override public synchronized String currentToken() throws IOException { return accessToken(); }
    @Override public synchronized String refreshAfterUnauthorized(String rejectedToken) throws IOException {
        var token = token();
        if (!token.accessToken().equals(rejectedToken)) return token.accessToken();
        return refresh(token).accessToken();
    }
    private OAuthTokenStore.Token token() throws IOException {
        var token = revoked ? null : store.find(resource, issuer, clientId);
        if (token == null) throw new IOException("OAuth 尚未登录，请使用 /mcp login <name>");
        return token;
    }
    private OAuthTokenStore.Token refresh(OAuthTokenStore.Token old) throws IOException {
        if (old.refreshToken() == null) throw new IOException("OAuth 没有 refresh token，请重新 /mcp login <name>");
        FormBody form = new FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", old.refreshToken())
                .add("client_id", clientId).add("resource", resource).build();
        var next = OAuthFlow.exchange(resource, issuer, clientId, old.tokenEndpoint(), form, old.refreshToken(), store, http);
        store.save(next);
        return next;
    }
    /** logout 后旧传输即使仍被持有也不能再取到令牌。 */
    public synchronized void revoke() { revoked = true; }
}
