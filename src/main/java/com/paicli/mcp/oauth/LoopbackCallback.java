package com.paicli.mcp.oauth;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 简单的单次 loopback HTTP 接收器；限制请求头与等待时间，不读取请求正文。 */
public final class LoopbackCallback implements OAuthFlow.Callback {
    private final ServerSocket server;
    private final String path = "/oauth/callback";
    public LoopbackCallback() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
    }
    @Override public String redirectUri() { return "http://127.0.0.1:" + server.getLocalPort() + path; }
    @Override public Map<String, String> await(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            server.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, (deadline - System.nanoTime()) / 1_000_000)));
            try (Socket socket = server.accept()) {
                socket.setSoTimeout((int) Math.max(1, Math.min(2000, (deadline - System.nanoTime()) / 1_000_000)));
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int c;
                    int tail = 0;
                    long requestDeadline = Math.min(deadline, System.nanoTime() + Duration.ofSeconds(2).toNanos());
                    while ((c = socket.getInputStream().read()) != -1) {
                        if (System.nanoTime() > requestDeadline || bytes.size() >= 16_384) throw new IOException();
                        bytes.write(c);
                        tail = (tail << 8) | c;
                        if (tail == 0x0d0a0d0a) break;
                    }
                    String raw = bytes.toString(StandardCharsets.US_ASCII);
                    String[] first = raw.split("\r\n", 2)[0].split(" ");
                    if (first.length != 3 || !first[0].equals("GET") || !first[2].equals("HTTP/1.1")
                            || !raw.endsWith("\r\n\r\n")) throw new IOException();
                    URI target = URI.create(first[1]);
                    if (target.isAbsolute() || target.getRawAuthority() != null || target.getRawFragment() != null || !path.equals(target.getRawPath())) throw new IOException();
                    Map<String, String> params = parameters(target.getRawQuery());
                    respond(socket, 200, "Return to PaiCLI to see the login result.");
                    return params;
                } catch (Exception e) { respond(socket, 400, "Invalid OAuth callback."); }
            } catch (SocketTimeoutException e) { /* 总截止时间仍生效 */ }
        }
        throw new IOException("OAuth 登录等待超时，请重新 /mcp login");
    }
    static Map<String, String> parameters(String query) throws IOException {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        try {
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                if (result.putIfAbsent(key, value) != null) throw new IOException();
            }
            return result;
        } catch (Exception e) { throw new IOException("OAuth callback 参数无效或重复"); }
    }
    private static void respond(Socket socket, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        String reason = status == 200 ? "OK" : "Bad Request";
        socket.getOutputStream().write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: text/plain\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
    }
    @Override public void close() throws IOException { server.close(); }
}
