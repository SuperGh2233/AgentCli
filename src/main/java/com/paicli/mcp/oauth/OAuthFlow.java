package com.paicli.mcp.oauth;

import okhttp3.*;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** 公共客户端的单次授权；浏览器和回调均可注入，测试不打开浏览器。 */
public final class OAuthFlow {
    @FunctionalInterface public interface Browser { boolean open(URI uri) throws Exception; }
    public interface Callback extends AutoCloseable {
        String redirectUri();
        Map<String, String> await(Duration timeout) throws IOException;
        @Override void close() throws IOException;
    }
    @FunctionalInterface public interface CallbackFactory { Callback open() throws IOException; }
    private final Browser browser;
    private final CallbackFactory callbacks;
    private final OAuthDiscovery discovery;
    private final OAuthTokenStore store;

    public OAuthFlow(OAuthTokenStore store) {
        this(store, new OAuthDiscovery(), uri -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false;
            Desktop.getDesktop().browse(uri); return true;
        }, LoopbackCallback::new);
    }
    public OAuthFlow(OAuthTokenStore store, OAuthDiscovery discovery, Browser browser, CallbackFactory callbacks) {
        this.store = store; this.discovery = discovery; this.browser = browser; this.callbacks = callbacks;
    }
    public void login(String resource, String clientId, List<String> configuredScopes, Consumer<String> output) throws IOException {
        login(resource, clientId, configuredScopes, output, com.paicli.mcp.protocol.McpProtocolVersion.LEGACY);
    }
    public void login(String resource, String clientId, List<String> configuredScopes, Consumer<String> output,
                      com.paicli.mcp.protocol.McpProtocolVersion protocolVersion) throws IOException {
        OAuthDiscovery.Metadata metadata = discovery.discover(resource, protocolVersion);
        String verifier = random();
        String state = random();
        try (Callback callback = callbacks.open()) {
            HttpUrl.Builder auth = discovery.http.endpoint(metadata.authorizationEndpoint()).newBuilder()
                    .addQueryParameter("response_type", "code").addQueryParameter("client_id", clientId)
                    .addQueryParameter("redirect_uri", callback.redirectUri()).addQueryParameter("state", state)
                    .addQueryParameter("code_challenge", challenge(verifier)).addQueryParameter("code_challenge_method", "S256")
                    .addQueryParameter("resource", metadata.resource());
            // 配置是用户预注册 scope 的允许范围，不能覆盖服务端的权威指引。
            List<String> scopes = selectScopes(metadata, configuredScopes);
            if (!scopes.isEmpty()) auth.addQueryParameter("scope", String.join(" ", scopes));
            URI authorization = auth.build().uri();
            boolean opened;
            try { opened = browser.open(authorization); } catch (Exception e) { opened = false; }
            if (!opened) output.accept("请手动打开授权地址（180 秒内完成）：\n" + authorization);
            else output.accept("已打开浏览器，请在 180 秒内完成 MCP 登录。");
            Map<String, String> params = callback.await(Duration.ofSeconds(180));
            String code = validateResponse(params, state, metadata.issuer(), metadata.requireIss());
            FormBody form = new FormBody.Builder().add("grant_type", "authorization_code")
                    .add("client_id", clientId).add("code", code).add("code_verifier", verifier)
                    .add("redirect_uri", callback.redirectUri()).add("resource", metadata.resource()).build();
            store.save(exchange(metadata.resource(), metadata.issuer(), clientId, metadata.tokenEndpoint(), form, null, store, discovery.http));
        }
    }
    static List<String> selectScopes(OAuthDiscovery.Metadata metadata, List<String> allowed) throws IOException {
        if (allowed == null) return metadata.scopes();
        if (metadata.challengeScopes()) {
            if (!allowed.containsAll(metadata.scopes()))
                throw new IOException("OAuth 服务要求的 scope 超出配置允许范围，请检查 oauth.scopes");
            return metadata.scopes();
        }
        return metadata.scopes().stream().filter(allowed::contains).distinct().toList();
    }

    public static String validateResponse(Map<String, String> params, String state, String issuer, boolean requireIss) throws IOException {
        String actualState = params.get("state");
        if (actualState == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), actualState.getBytes(StandardCharsets.UTF_8)))
            throw new IOException("OAuth state 校验失败，请重新登录");
        String actualIssuer = params.get("iss");
        if ((requireIss && actualIssuer == null) || (actualIssuer != null && !issuer.equals(actualIssuer)))
            throw new IOException("OAuth 授权响应 issuer 校验失败");
        if (params.containsKey("error")) throw new IOException("授权服务器拒绝登录，请检查授权与 scope 后重试");
        String code = params.get("code");
        if (code == null || code.isBlank()) throw new IOException("OAuth 回调缺少 code");
        return code;
    }
    static OAuthTokenStore.Token exchange(String resource, String issuer, String clientId, String endpoint,
                                          FormBody form, String oldRefresh, OAuthTokenStore store, OAuthHttp http) throws IOException {
        try (Response response = http.client().newCall(new Request.Builder().url(http.endpoint(endpoint)).post(form).build()).execute()) {
            var json = OAuthHttp.json(response);
            if (!response.isSuccessful()) {
                if (json.path("error").asText().equals("invalid_grant")) {
                    store.delete(resource, issuer, clientId);
                    throw new IOException("OAuth invalid_grant：旧凭证已清除，请重新 /mcp login <name>");
                }
                throw new IOException("OAuth token 请求失败，请重新登录或检查授权服务器");
            }
            String access = json.path("access_token").asText("");
            String refresh = json.has("refresh_token") ? json.path("refresh_token").asText() : oldRefresh;
            if (!json.path("access_token").isTextual() || !json.path("token_type").isTextual()
                    || (json.has("refresh_token") && !json.get("refresh_token").isTextual())
                    || (json.has("expires_in") && !json.get("expires_in").isIntegralNumber())
                    || !json.path("token_type").asText().equalsIgnoreCase("Bearer") || !access.matches("[A-Za-z0-9\\-._~+/]+=*")
                    || (refresh != null && refresh.isBlank())) throw new IOException("OAuth token 响应无效");
            long seconds = json.path("expires_in").asLong(0);
            if (seconds < 0 || seconds > 315_360_000) throw new IOException("OAuth token expires_in 无效");
            long expiresAt = json.has("expires_in") ? System.currentTimeMillis() + seconds * 1000 : 0;
            return new OAuthTokenStore.Token(resource, issuer, clientId, endpoint, access, refresh, expiresAt);
        } catch (IOException e) {
            // 只传播本地固定错误；HTTP 库的错误可能带 URL，远端正文永不出现在错误里。
            if (e.getMessage() != null && e.getMessage().startsWith("OAuth ")) throw e;
            throw new IOException("OAuth token 请求失败，请检查网络后重试");
        }
    }
    public static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JDK 缺少 SHA-256"); }
    }
    private static String random() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
