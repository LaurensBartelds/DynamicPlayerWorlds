package nl.gzmn.playerworlds.core.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrometheusEndpointTest {

    @Test
    @DisplayName("GET /metrics returns prometheus text from the registry")
    void servesMetrics() throws Exception {
        try (WorldsMetrics metrics = WorldsMetrics.create()) {
            metrics.setWorldsLoaded(4);
            // Bind an ephemeral free port; port 0 means "disabled" in MetricsSettings.
            MetricsSettings settings = new MetricsSettings("127.0.0.1", findFreePort());

            try (PrometheusEndpoint endpoint = PrometheusEndpoint.start(metrics, settings)) {
                assertThat(endpoint).isNotNull();
                URI uri = URI.create("http://127.0.0.1:" + endpoint.address().getPort() + "/metrics");
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(uri)
                                .GET()
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("worlds_loaded").contains("4.0");
            }
        }
    }

    @Test
    @DisplayName("disabled settings open no socket")
    void disabledReturnsNull() throws Exception {
        try (WorldsMetrics metrics = WorldsMetrics.create()) {
            assertThat(PrometheusEndpoint.start(metrics, MetricsSettings.disabled()))
                    .isNull();
        }
    }

    private static int findFreePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
