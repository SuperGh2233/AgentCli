package com.paicli.mcp.config;

import java.util.List;

/** 只支持预注册的公共客户端。故意不忽略未知字段，避免误收 client_secret。 */
public final class McpOAuthConfig {
    private String clientId;
    private List<String> scopes;
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public void validate() {
        if (clientId == null || clientId.isBlank() || clientId.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("oauth.clientId 必须是预注册的公共客户端 ID");
        if (scopes != null && scopes.stream().anyMatch(s -> s == null || !s.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")))
            throw new IllegalArgumentException("oauth.scopes 必须是有效的 OAuth scope 字符串列表");
    }
}
