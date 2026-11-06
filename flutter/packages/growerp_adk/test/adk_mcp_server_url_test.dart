import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_adk/src/adk_mcp_server_dialog.dart';

void main() {
  group('external MCP URL validation', () {
    test('allows an authenticated HTTPS gateway URL without embedding credentials', () {
      expect(validateExternalMcpUrl('https://waha.indexesstore.com/serpapi/mcp'), isNull);
      expect(validateExternalMcpUrl('  https://example.org/mcp  '), isNull);
    });

    test('allows loopback HTTP for local integration tests', () {
      expect(validateExternalMcpUrl('http://localhost:8080/mcp/sse'), isNull);
      expect(validateExternalMcpUrl('http://127.0.0.1:8080/mcp'), isNull);
      expect(validateExternalMcpUrl('http://[::1]:8080/mcp'), isNull);
    });

    test('rejects public HTTP, credentials, and fragments', () {
      expect(validateExternalMcpUrl('http://example.org/mcp'), isNotNull);
      expect(validateExternalMcpUrl('https://user:pass@example.org/mcp'), isNotNull);
      expect(validateExternalMcpUrl('https://example.org/mcp#token'), isNotNull);
    });

    test('rejects missing or malformed URLs', () {
      expect(validateExternalMcpUrl(null), isNotNull);
      expect(validateExternalMcpUrl(' '), isNotNull);
      expect(validateExternalMcpUrl('example.org/mcp'), isNotNull);
      expect(validateExternalMcpUrl('https:///'), isNotNull);
    });
  });
}
