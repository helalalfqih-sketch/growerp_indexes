import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:growerp_user_company/src/company_user/views/company_user_files_dialog.dart';

void main() {
  group('customer CSV UTF-8 import', () {
    const csv =
        'Id,type,name,role,email,URL,telephone number,address1,address2,'
        'postalCode,city,province/state,country,countryId,companyId,companyName\n'
        'p1,Company,ماكسوم,Customer,,,,,,,,,,,,\n';

    test('preserves Arabic customer names and the first row', () {
      expect(decodeCompanyUserCsv(utf8.encode(csv)), csv);
    });

    test('removes an Excel UTF-8 BOM without damaging the header', () {
      final bytes = <int>[0xEF, 0xBB, 0xBF, ...utf8.encode(csv)];
      expect(decodeCompanyUserCsv(bytes), csv);
    });

    test('rejects invalid UTF-8 instead of corrupting customer names', () {
      expect(
        () => decodeCompanyUserCsv(<int>[0xFF, 0xFE, 0xC0]),
        throwsFormatException,
      );
    });
  });
}
