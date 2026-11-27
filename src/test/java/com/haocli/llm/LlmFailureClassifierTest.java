package com.haocli.llm;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmFailureClassifierTest {
    @Test
    void classifiesOnlyConfirmedRetryableFailuresAsTransient() {
        assertEquals(LlmFailureClassifier.Category.TRANSIENT,
                LlmFailureClassifier.classify(new LlmHttpException(429, null, "rate limited")));
        assertEquals(LlmFailureClassifier.Category.TRANSIENT,
                LlmFailureClassifier.classify(new LlmHttpException(503, null, "unavailable")));
        assertEquals(LlmFailureClassifier.Category.TRANSIENT,
                LlmFailureClassifier.classify(new LlmStreamingApiException("retry", true)));
        assertEquals(LlmFailureClassifier.Category.TRANSIENT,
                LlmFailureClassifier.classify(new SocketTimeoutException("timeout")));
        assertEquals(LlmFailureClassifier.Category.TRANSIENT,
                LlmFailureClassifier.classify(new EOFException("closed")));
    }

    @Test
    void keepsCredentialRequestAndUnknownAdapterFailuresOutOfInfraBucket() {
        assertEquals(LlmFailureClassifier.Category.AUTHENTICATION,
                LlmFailureClassifier.classify(new LlmHttpException(401, null, "unauthorized")));
        assertEquals(LlmFailureClassifier.Category.INVALID_REQUEST,
                LlmFailureClassifier.classify(new LlmHttpException(400, null, "bad request")));
        assertEquals(LlmFailureClassifier.Category.INVALID_RESPONSE,
                LlmFailureClassifier.classify(new LlmStreamingApiException("bad stream", false)));
        assertEquals(LlmFailureClassifier.Category.UNKNOWN,
                LlmFailureClassifier.classify(new IOException("adapter failed")));
    }

    @Test
    void insufficientBalanceIsBillingNotInvalidRequest() {
        assertEquals(LlmFailureClassifier.Category.BILLING,
                LlmFailureClassifier.classify(new LlmHttpException(402, null, "Insufficient Balance")));
    }

    @Test
    void remoteHandshakeTerminationIsTransientButCertificateFailureIsNot() {
        SSLHandshakeException terminated = new SSLHandshakeException("Remote host terminated the handshake");
        terminated.initCause(new EOFException("SSL peer shut down incorrectly"));
        assertEquals(LlmFailureClassifier.Category.TRANSIENT, LlmFailureClassifier.classify(terminated));
        assertEquals(LlmFailureClassifier.Category.TRANSIENT,
                LlmFailureClassifier.classify(new SSLHandshakeException("Remote host terminated the handshake")));

        SSLHandshakeException badCertificate = new SSLHandshakeException("PKIX path building failed");
        badCertificate.initCause(new java.security.cert.CertificateException("unable to find valid certification path"));
        assertEquals(LlmFailureClassifier.Category.UNKNOWN, LlmFailureClassifier.classify(badCertificate));
    }
}
