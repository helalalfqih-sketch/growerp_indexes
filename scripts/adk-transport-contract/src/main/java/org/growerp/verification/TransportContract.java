package org.growerp.verification;

import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.SseServerParameters;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import java.util.Map;

/**
 * Compile-time verification of the external transport APIs against the exact
 * google-adk version used in moqui-adk. No network connections are made.
 */
public final class TransportContract {
  private TransportContract() {}

  public static McpToolset http(String url, Map<String, String> headers) {
    return new McpToolset(StreamableHttpServerParameters.builder()
        .url(url)
        .headers(headers)
        .build());
  }

  public static McpToolset sse(String url, Map<String, String> headers) {
    return new McpToolset(SseServerParameters.builder()
        .url(url)
        .headers(headers)
        .build());
  }
}
