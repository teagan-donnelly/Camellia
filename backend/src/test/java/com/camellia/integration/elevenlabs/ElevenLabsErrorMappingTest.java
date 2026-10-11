package com.camellia.integration.elevenlabs;

import com.camellia.config.CamelliaProperties;
import com.camellia.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** How ElevenLabs error replies are reported. ElevenLabs answers an exhausted quota with HTTP 401, like a bad key. */
class ElevenLabsErrorMappingTest {

    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** A client whose ElevenLabs base URL is a local server that always answers with this status and body. */
    ElevenLabsClient clientAnswering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        CamelliaProperties props = new CamelliaProperties();
        props.getElevenlabs().setApiKey("test-key");
        props.getElevenlabs().setVoiceId("voice-1");
        props.getElevenlabs().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getElevenlabs().setMaxRetries(0);
        return new ElevenLabsClient(props);
    }

    @Test
    void exhaustedQuotaIsReportedAsQuotaNotAsARejectedKey() throws IOException {
        ElevenLabsClient client = clientAnswering(401, """
                {"detail":{"status":"quota_exceeded","message":"This request exceeds your quota of 10000. You have 1137 credits remaining, while 1580 credits are required for this request."}}""");
        ApiException e = assertThrows(ApiException.class, () -> client.synthesize("Hello"));
        assertThat(e.code()).isEqualTo("ELEVENLABS_QUOTA_EXCEEDED");
        assertThat(e.getMessage()).contains("quota is used up").contains("1137 credits remaining").doesNotContain("rejected the API key");
        assertThat(client.status().lastError()).contains("quota is used up");
    }

    @Test
    void anInvalidKeyIsStillReportedAsAKeyProblem() throws IOException {
        ElevenLabsClient client = clientAnswering(401, """
                {"detail":{"status":"invalid_api_key","message":"Invalid API key"}}""");
        ApiException e = assertThrows(ApiException.class, () -> client.synthesize("Hello"));
        assertThat(e.code()).isEqualTo("ELEVENLABS_AUTH_FAILED");
        assertThat(e.getMessage()).contains("rejected the API key");
    }
}
