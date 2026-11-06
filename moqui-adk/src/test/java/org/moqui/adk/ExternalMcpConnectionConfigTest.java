package org.moqui.adk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression tests for tenant-supplied external MCP endpoints and headers. */
class ExternalMcpConnectionConfigTest {
    @Test
    void httpTransportUsesHttpsEndpoint() {
        Map<String, Object> result = AdkManager.externalMcpConnectionConfig(Map.of(
                "url", "https://waha.indexesstore.com/serpapi/mcp",
                "transport", "http",
                "headersJson", "{\"Authorization\":\"Bearer temporary\"}"));

        assertEquals("http", result.get("transport"));
        assertEquals("https://waha.indexesstore.com/serpapi/mcp", result.get("endpoint"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) result.get("headers");
        assertEquals("Bearer temporary", headers.get("Authorization"));
    }

    @Test
    void sseRemainsDefaultForLegacyRegistrations() {
        Map<String, Object> result = AdkManager.externalMcpConnectionConfig(Map.of(
                "url", "https://example.org/mcp/sse"));
        assertEquals("sse", result.get("transport"));
    }

    @Test
    void localHttpIsOnlyAllowedOnLoopback() {
        assertEquals("http", AdkManager.externalMcpConnectionConfig(Map.of(
                "url", "http://127.0.0.1:8080/mcp",
                "transport", "http")).get("transport"));
        for (String url : new String[] {
                "http://example.org/mcp",
                "http://172.18.0.8/mcp",
                "https://user:password@example.org/mcp",
                "https://example.org/mcp#token",
                "file:///etc/passwd",
                "https:///mcp"}) {
            assertThrows(Exception.class,
                    () -> AdkManager.externalMcpConnectionConfig(Map.of("url", url)),
                    "should reject: " + url);
        }
    }

    @Test
    void rejectsUnknownTransportAndUnsafeHeaders() {
        assertThrows(IllegalArgumentException.class,
                () -> AdkManager.externalMcpConnectionConfig(Map.of(
                        "url", "https://example.org/mcp", "transport", "grpc")));
        assertThrows(IllegalArgumentException.class,
                () -> AdkManager.externalMcpConnectionConfig(Map.of(
                        "url", "https://example.org/mcp", "headersJson",
                        "{\"X-Debug\":\"safe\\r\\nInjected: yes\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> AdkManager.externalMcpConnectionConfig(Map.of(
                        "url", "https://example.org/mcp", "headersJson",
                        "{\"Host\":\"evil.example\"}")));
    }

    @Test
    void rejectsNonObjectHeadersWithoutLoggingCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> AdkManager.externalMcpConnectionConfig(Map.of(
                        "url", "https://example.org/mcp", "headersJson", "[1,2]")));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> AdkManager.externalMcpConnectionConfig(Map.of(
                        "url", "https://example.org/mcp", "headersJson",
                        "{secret-not-valid-json}")));
        assertFalse(exception.getMessage().contains("secret-not-valid-json"));
    }
}
