package com.paicli.mcp.oauth;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LoopbackCallbackTest {
    String request(int port,String target) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(3000);socket.getOutputStream().write(("GET "+target+" HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(),StandardCharsets.US_ASCII);
        }
    }
    @Test void exactPathNoAuthorityAndDuplicateParameters() throws Exception {
        try(var callback=new LoopbackCallback()) {
            var executor=Executors.newSingleThreadExecutor();
            try {
                var result=executor.submit(()->callback.await(Duration.ofSeconds(5)));int port=URI.create(callback.redirectUri()).getPort();
                assertEquals("127.0.0.1",URI.create(callback.redirectUri()).getHost());
                assertTrue(request(port,"//evil.example/oauth/callback?state=s&code=c").startsWith("HTTP/1.1 400 Bad Request\r\n"));
                assertTrue(request(port,"/oauth/callback/extra?code=c").startsWith("HTTP/1.1 400 Bad Request\r\n"));
                assertTrue(request(port,"/oauth/callback?state=a&state=b").startsWith("HTTP/1.1 400 Bad Request\r\n"));
                assertTrue(request(port,"/oauth/callback?state=s&code=c&iss=https%3A%2F%2Fas.example").startsWith("HTTP/1.1 200 OK\r\n"));
                assertEquals("https://as.example",result.get(2,TimeUnit.SECONDS).get("iss"));
            } finally {executor.shutdownNow();}
        }
    }
    @Test void boundedTimeoutAndSize() throws Exception {
        try(var callback=new LoopbackCallback()) {
            assertThrows(IOException.class,()->callback.await(Duration.ofMillis(20)));
        }
        try(var callback=new LoopbackCallback()) {
            var executor=Executors.newSingleThreadExecutor();
            try {
                var result=executor.submit(()->callback.await(Duration.ofSeconds(3)));int port=URI.create(callback.redirectUri()).getPort();
                try { assertTrue(request(port,"/oauth/callback?code="+"a".repeat(17000)).contains("400")); }
                catch (SocketException expected) { /* 超限请求也可以被直接关闭连接 */ }
                request(port,"/oauth/callback?state=s&code=c");assertEquals("c",result.get().get("code"));
            } finally {executor.shutdownNow();}
        }
    }
}
