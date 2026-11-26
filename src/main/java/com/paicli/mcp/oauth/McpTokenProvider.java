package com.paicli.mcp.oauth;

import java.io.IOException;

/** HTTP 传输只知道读取令牌、401 后刷新一次，以及关闭时读取已有令牌。 */
public interface McpTokenProvider {
    default void validateResource(String url) throws IOException { }
    String accessToken() throws IOException;
    String refreshAfterUnauthorized(String rejectedToken) throws IOException;
    default String currentToken() throws IOException { return accessToken(); }
}
