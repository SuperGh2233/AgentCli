package com.paicli.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import java.io.IOException;
import java.util.*;
import com.paicli.mcp.protocol.McpInitializeRequest;
import com.paicli.mcp.protocol.McpProtocolVersion;

/** RFC9728 资源发现，以及 RFC8414/OIDC 授权服务器发现。 */
public final class OAuthDiscovery {
    final OAuthHttp http;
    public OAuthDiscovery() { this(new OAuthHttp()); }
    OAuthDiscovery(OAuthHttp http) { this.http = http; }
    public record Metadata(String resource, String issuer, String authorizationEndpoint, String tokenEndpoint,
                           boolean requireIss, List<String> scopes, boolean challengeScopes) {}
    /** OkHttp 的 challenge parser 负责区分多个认证 scheme 和带逗号的引号字符串。 */
    public static Map<String, String> challenge(Response response) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Challenge c : response.challenges()) {
            if (!c.scheme().equalsIgnoreCase("Bearer")) continue;
            for (var entry : c.authParams().entrySet()) {
                if (entry.getKey() != null && result.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()) != null)
                    throw new IOException("OAuth Bearer challenge 参数重复");
            }
        }
        return result;
    }

    public String canonicalResource(String url) throws IOException { return OAuthHttp.resource(url); }

    public Metadata discover(String configuredResource) throws IOException {
        return discover(configuredResource, McpProtocolVersion.LEGACY);
    }

    public Metadata discover(String configuredResource, McpProtocolVersion protocolVersion) throws IOException {
        String resource = OAuthHttp.resource(configuredResource);
        HttpUrl endpoint = OAuthHttp.resourceUrl(configuredResource);
        Map<String, String> challenge = Map.of();
        var initialize = OAuthHttp.JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize");
        initialize.set("params", McpInitializeRequest.toJson());
        if (protocolVersion.modern()) {
            initialize.put("method", "server/discover");
            initialize.set("params", protocolVersion.decorate(OAuthHttp.JSON.createObjectNode()));
        }
        Request.Builder probeBuilder = new Request.Builder().url(endpoint)
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .post(RequestBody.create(initialize.toString(), MediaType.get("application/json")));
        if (protocolVersion.modern()) {
            probeBuilder.header("MCP-Protocol-Version", protocolVersion.value()).header("Mcp-Method", "server/discover");
        }
        Request probe = probeBuilder.build();
        try (Response r = http.client().newCall(probe).execute()) {
            if (r.code() == 401) challenge = challenge(r);
            else if (!r.isSuccessful()) throw new IOException("MCP 探测失败，需返回 401 或成功响应");
        } catch (IOException e) { throw new IOException("OAuth MCP 探测失败，请检查资源地址与认证配置"); }
        List<String> candidates = challenge.containsKey("resource_metadata")
                ? List.of(challenge.get("resource_metadata"))
                : resourceMetadataUrls(configuredResource);
        JsonNode prm = null;
        for (String candidate : candidates) {
            prm = http.getResourceMetadata(candidate);
            if (prm != null) break;
        }
        if (prm == null) throw new IOException("找不到 OAuth Protected Resource Metadata");
        if (!resource.equals(prm.path("resource").asText())) throw new IOException("OAuth 资源元数据 resource 不匹配");
        JsonNode servers = prm.path("authorization_servers");
        if (!servers.isArray() || servers.isEmpty() || !servers.get(0).isTextual())
            throw new IOException("资源元数据缺少 authorization_servers");
        String issuer = servers.get(0).asText();
        JsonNode as = authorizationMetadata(issuer);
        if (!strings(as.path("response_types_supported")).contains("code"))
            throw new IOException("授权服务器未明确支持 response_type=code，不能登录");
        if (as.has("grant_types_supported")
                && !strings(as.path("grant_types_supported")).contains("authorization_code"))
            throw new IOException("授权服务器未明确支持 authorization_code grant，不能登录");
        if (!strings(as.path("code_challenge_methods_supported")).contains("S256"))
            throw new IOException("授权服务器未明确支持 PKCE S256，不能登录");
        // RFC 8414 未声明时默认 client_secret_basic；本实现只允许预注册公共客户端。
        if (!strings(as.path("token_endpoint_auth_methods_supported")).contains("none"))
            throw new IOException("授权服务器未明确支持公共客户端 token endpoint auth method none，不能登录");
        if (as.has("authorization_response_iss_parameter_supported") && !as.get("authorization_response_iss_parameter_supported").isBoolean())
            throw new IOException("OAuth issuer 响应能力必须是布尔值");
        String auth = as.path("authorization_endpoint").asText();
        String token = as.path("token_endpoint").asText();
        http.endpoint(auth); http.endpoint(token);
        if (http.endpoint(auth).query() != null || http.endpoint(token).query() != null)
            throw new IOException("OAuth endpoint 不能含预置 query 参数");
        List<String> scopes = challenge.containsKey("scope")
                ? Arrays.stream(challenge.get("scope").split(" +")).filter(s -> !s.isEmpty()).toList()
                : strings(prm.path("scopes_supported"));
        return new Metadata(resource, issuer, auth, token,
                as.path("authorization_response_iss_parameter_supported").asBoolean(false), scopes, challenge.containsKey("scope"));
    }

    public static List<String> resourceMetadataUrls(String resource) throws IOException {
        HttpUrl url = OAuthHttp.resourceUrl(resource);
        String origin = url.newBuilder().encodedPath("/").query(null).build().toString();
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (!url.encodedPath().equals("/")) urls.add(origin + ".well-known/oauth-protected-resource" + url.encodedPath());
        urls.add(origin + ".well-known/oauth-protected-resource");
        return List.copyOf(urls);
    }

    public JsonNode authorizationMetadata(String issuer) throws IOException {
        HttpUrl url = http.endpoint(issuer);
        if (url.query() != null) throw new IOException("OAuth issuer 不能含 query");
        String origin = url.newBuilder().encodedPath("/").build().toString();
        String path = url.encodedPath().equals("/") ? "" : url.encodedPath();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(origin + ".well-known/oauth-authorization-server" + path);
        candidates.add(origin + ".well-known/openid-configuration" + path);
        if (!path.isEmpty()) candidates.add(origin + path.substring(1).replaceAll("/$", "") + "/.well-known/openid-configuration");
        for (String candidate : candidates) {
            JsonNode metadata = http.get(candidate);
            if (metadata == null) continue;
            if (!issuer.equals(metadata.path("issuer").asText())) throw new IOException("授权服务器元数据 issuer 不匹配");
            return metadata;
        }
        throw new IOException("找不到授权服务器 RFC8414/OIDC 元数据");
    }

    private static List<String> strings(JsonNode node) throws IOException {
        if (node.isMissingNode()) return List.of();
        if (!node.isArray()) throw new IOException("OAuth 元数据列表格式无效");
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw new IOException("OAuth 元数据列表格式无效");
            values.add(item.asText());
        }
        return List.copyOf(values);
    }
}
