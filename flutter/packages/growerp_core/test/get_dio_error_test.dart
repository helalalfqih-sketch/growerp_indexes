import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_core/growerp_core.dart';
import 'package:shared_preferences/shared_preferences.dart';

DioException response(Object? data, {int status = 400}) {
  final options = RequestOptions(path: 'rest/s1/growerp/100/X');
  return DioException(
    requestOptions: options,
    response: Response(requestOptions: options, statusCode: status, data: data),
  );
}

DioException noResponse(DioExceptionType type) =>
    DioException(requestOptions: RequestOptions(), type: type);

// getDioError emits localization keys; the UI translates them using CoreLocalizations.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('getDioError backend errors', () {
    test('getDioError_errorsMap_returnsTrimmedErrorText', () async {
      expect(
        await getDioError(response({'errors': '  Product name required \n'})),
        'Product name required',
      );
    });

    test('getDioError_errorsAsJsonString_isDecodedFirst', () async {
      expect(
        await getDioError(response('{"errors": "Not allowed"}')),
        'Not allowed',
      );
    });

    test('getDioError_nestedAiError_returnsInnerMessage', () async {
      // Arrange: an LLM provider error relayed by the backend
      final error = response({
        'errors':
            'Failed to generate: { "error": { "message": "Model overloaded" } }',
      });
      // Act + Assert
      expect(await getDioError(error), 'Model overloaded');
    });

    test('getDioError_escapedNewlines_becomeRealNewlines', () async {
      expect(
        await getDioError(response({'errors': r'line one\nline two'})),
        'line one\nline two',
      );
    });

    test('getDioError_noUserAuthzText_saysSignedOut', () async {
      // Arrange: a request that left the client after logout
      final error = response({
        'errors': 'User [No User] is not authorized for View on REST Path',
      }, status: 403);
      // Act + Assert
      expect(
        await getDioError(error),
        'signedOutLoginAgain',
      );
    });

    test('getDioError_errorCodeOnly_reportsTheCode', () async {
      expect(
        await getDioError(response({'errorCode': 500})),
        'errorCodeMessage:500',
      );
    });

    test('getDioError_htmlBody_isAGenericServerError', () async {
      expect(
        await getDioError(response('<html>Bad gateway</html>', status: 502)),
        'serverErrorOccurred',
      );
    });

    test('getDioError_emptyErrors_fallsBackToConnectionError', () async {
      expect(
        await getDioError(response({'errors': ''})),
        'serverConnectionError',
      );
    });

    test('getDioError_loginKeyInvalid_clearsKeyAndAsksToLogin', () async {
      // Arrange
      SharedPreferences.setMockInitialValues({'apiKey': 'stale'});
      // Act
      final message = await getDioError(
        response({'errors': 'Login key not valid'}, status: 401),
      );
      // Assert
      expect(message, 'loginKeyExpired');
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getString('apiKey'), isNull);
    });
  });

  group('getDioError network errors', () {
    final cases = {
      DioExceptionType.connectionError: 'networkConnectionError',
      DioExceptionType.receiveTimeout: 'requestTimeout',
      DioExceptionType.connectionTimeout: 'connectionTimeout',
      DioExceptionType.sendTimeout: 'sendTimeout',
      DioExceptionType.cancel: 'requestCancelled',
      DioExceptionType.unknown: 'networkError',
    };
    for (final MapEntry(key: type, value: text) in cases.entries) {
      test('getDioError_${type.name}_says$text', () async {
        expect(await getDioError(noResponse(type)), text);
      });
    }
  });

  group('getDioError other errors', () {
    test('getDioError_formatException_saysInvalidFormat', () async {
      expect(
        await getDioError(const FormatException('bad')),
        'invalidDataFormat',
      );
    });

    test('getDioError_anythingElse_usesToString', () async {
      expect(await getDioError(StateError('boom')), 'Bad state: boom');
    });
  });
}
