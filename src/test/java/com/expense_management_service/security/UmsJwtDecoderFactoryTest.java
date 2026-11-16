package com.expense_management_service.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UmsJwtDecoderFactoryTest {

    @Test
    void stalledUms_isReportedAsUnavailable_withinTheReadTimeout() throws IOException {
        // The socket accepts connections but never answers, which is what a stalled UMS looks like from here.
        try (ServerSocket stalled = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            String issuer = "http://127.0.0.1:" + stalled.getLocalPort() + "/ums";
            ResilientJwtDecoder decoder = new ResilientJwtDecoder(() -> UmsJwtDecoderFactory.fromIssuer(issuer));

            long started = System.nanoTime();
            assertThrows(UmsUnavailableException.class, () -> decoder.decode("not-a-real-token"));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            // The read timeout is 5 seconds. The call must end near that, not hang.
            assertTrue(elapsedMs >= 4_500, "returned too early: " + elapsedMs + " ms");
            assertTrue(elapsedMs < 10_000, "did not honour the read timeout: " + elapsedMs + " ms");
            assertFalse(decoder.isInitialized());
        }
    }

    @Test
    void timeoutsAreConfiguredWithinTheAgreedRange() {
        assertTrue(UmsJwtDecoderFactory.CONNECT_TIMEOUT.compareTo(Duration.ofSeconds(3)) >= 0
                && UmsJwtDecoderFactory.CONNECT_TIMEOUT.compareTo(Duration.ofSeconds(5)) <= 0);
        assertTrue(UmsJwtDecoderFactory.READ_TIMEOUT.equals(Duration.ofSeconds(5)));
    }
}
