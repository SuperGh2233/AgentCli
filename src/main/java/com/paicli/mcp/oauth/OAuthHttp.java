package com.paicli.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/** OAuth 专用客户端：没有 MCP headers、cookies 或重定向。错误不带远端正文。 */
final class OAuthHttp {
    static final ObjectMapper JSON = new ObjectMapper();
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(15, TimeUnit.SECONDS).build();

    @FunctionalInterface interface EndpointValidator { HttpUrl validate(String address) throws IOException; }
    private final OkHttpClient client;
    private final EndpointValidator endpoints;
    OAuthHttp() { this(CLIENT, OAuthHttp::httpsUrl); }
    // 仅包内注入测试端点策略，没有环境变量或用户配置可关闭 HTTPS。
    OAuthHttp(OkHttpClient client, EndpointValidator endpoints) { this.client = client; this.endpoints = endpoints; }
    HttpUrl endpoint(String address) throws IOException { return endpoints.validate(address); }
    OkHttpClient client() { return client; }
    static HttpUrl httpsUrl(String address) throws IOException {
        HttpUrl url = resourceUrl(address);
        if (!url.isHttps()) throw new IOException("OAuth 授权服务器 issuer 与端点必须使用 HTTPS");
        return url;
    }

    static HttpUrl resourceUrl(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            HttpUrl url = HttpUrl.get(value);
            boolean loopback = url.host().equals("127.0.0.1") || url.host().equals("localhost");
            if ((!url.isHttps() && !loopback) || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
            return url;
        } catch (RuntimeException e) { throw new IOException("OAuth 地址必须是 HTTPS，且不能含用户信息或 fragment"); }
    }

    static String resource(String value) throws IOException {
        HttpUrl url = resourceUrl(value);
        if (url.query() != null) throw new IOException("OAuth MCP resource URL 不能含 query");
        String canonical = url.toString();
        return url.encodedPath().equals("/") ? canonical.substring(0, canonical.length() - 1) : canonical;
    }

    static JsonNode json(Response response) throws IOException {
        try {
            if (response.body() == null) throw new IOException();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            var input = response.body().byteStream();
            while ((count = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
                if (buffer.size() > 1_048_576) throw new IOException();
            }
            byte[] bytes = buffer.toByteArray();
            if (bytes.length > 1_048_576) throw new IOException();
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject()) throw new IOException();
            return node;
        } catch (Exception e) { throw new IOException("OAuth 响应无效或超过大小限制"); }
    }

    JsonNode getResourceMetadata(String address) throws IOException { return get(resourceUrl(address)); }
    JsonNode get(String address) throws IOException { return get(endpoint(address)); }
    private JsonNode get(HttpUrl address) throws IOException {
        Request request = new Request.Builder().url(address).header("Accept", "application/json").build();
        try (Response r = client.newCall(request).execute()) {
            if (r.code() == 404 || r.code() == 405) return null;
            if (!r.isSuccessful()) throw new IOException();
            return json(r);
        } catch (IOException e) { throw new IOException("OAuth 元数据请求失败，请检查服务配置或网络"); }
    }
}
