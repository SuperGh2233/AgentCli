package com.haocli.llm;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Stable, message-free failure categories for benchmark and runtime orchestration. */
public final class LlmFailureClassifier {
    private LlmFailureClassifier() {
    }

    public static Category classify(IOException failure) {
        if (failure == null) {
            return Category.UNKNOWN;
        }
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof LlmHttpException http) {
                int status = http.statusCode();
                if (status == 408 || status == 429 || status >= 500) {
                    return Category.TRANSIENT;
                }
                if (status == 401 || status == 403) {
                    return Category.AUTHENTICATION;
                }
                if (status == 402) {
                    return Category.BILLING;
                }
                if (status >= 400 && status < 500) {
                    return Category.INVALID_REQUEST;
                }
                return Category.UNKNOWN;
            }
            if (current instanceof LlmStreamingApiException streaming) {
                return streaming.retryable() ? Category.TRANSIENT : Category.INVALID_RESPONSE;
            }
            if (isRemoteHandshakeTermination(current)) {
                return Category.TRANSIENT;
            }
            if (current instanceof LlmStreamInterruptedException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof EOFException) {
                return Category.TRANSIENT;
            }
            if (current instanceof InterruptedIOException) {
                return Category.CANCELLED_OR_TIMEOUT;
            }
        }
        return Category.UNKNOWN;
    }

    /**
     * 对端在 TLS 握手中途断开（JDK 报 “Remote host terminated the handshake”，原因链里是 EOF / socket 异常）。
     * 这是网络抖动，可以重试；证书校验失败等其他 SSL 错误不算。
     */
    static boolean isRemoteHandshakeTermination(Throwable failure) {
        if (!(failure instanceof SSLHandshakeException)) {
            return false;
        }
        String message = failure.getMessage();
        if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("terminated the handshake")) {
            return true;
        }
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof EOFException || cause instanceof SocketException) {
                return true;
            }
        }
        return false;
    }

    public enum Category {
        TRANSIENT,
        AUTHENTICATION,
        /** 402：账户余额不足或额度用完，重试没用，也不是 Agent 的问题。 */
        BILLING,
        INVALID_REQUEST,
        INVALID_RESPONSE,
        CANCELLED_OR_TIMEOUT,
        UNKNOWN
    }
}
