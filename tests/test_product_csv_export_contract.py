"""Offline, non-runtime contracts for the NASQO product CSV patch."""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


class ProductCsvContracts(unittest.TestCase):
    def test_xml_is_well_formed(self):
        for name in ("backend/service/growerp.rest.xml", "backend/service/growerp/100/ImportExportServices100.xml"):
            with self.subTest(name=name):
                ET.parse(ROOT / name)

    def test_device_route_requires_authentication(self):
        root = ET.parse(ROOT / "backend/service/growerp.rest.xml").getroot()
        routes = [r for r in root.iter("resource") if r.attrib.get("name") == "csv"]
        self.assertEqual(len(routes), 1)
        self.assertEqual(routes[0].attrib.get("require-authentication"), "true")
        self.assertEqual(routes[0].find("method").attrib.get("type"), "get")

    def test_csv_export_rejects_missing_identity_and_limit(self):
        s = read("backend/service/growerp/100/ImportExportServices100.xml")
        section = s.split('<service verb="get" noun="ExportProducts">', 1)[1].split('</service>', 1)[0]
        for phrase in ("!ec.user?.userAccount?.partyId", "!ownerPartyId || !companyPartyId", "products?.size() >= 9999", "applicationId: applicationId, limit: 9999"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, section)

    def test_email_template_matches_seed_and_failure_is_explicit(self):
        s = read("backend/service/growerp/100/ImportExportServices100.xml")
        seed = (ROOT / "backend/data/GrowerpAaSeedData.xml").read_text() if (ROOT / "backend/data/GrowerpAaSeedData.xml").exists() else None
        self.assertIn('emailTemplateId" value="NASQOExportProducts"', s)
        if seed is not None:
            self.assertIn('emailTemplateId="NASQOExportProducts"', seed)
        self.assertIn("entityName == 'Product' &amp;&amp; !emailSent", s)
        self.assertIn('email request was accepted', s)

    def test_separate_device_and_email_events(self):
        s = read("flutter/packages/growerp_catalog/lib/src/product/views/product_files_dialog.dart")
        self.assertIn("ProductDownloadToDevice()", s)
        self.assertIn("ProductDownload()", s)
        self.assertIn("FilePicker.saveFile", s)
        self.assertIn("utf8.encode(csv)", s)
        self.assertIn("key: const Key('downloadToDevice')", s)

    def test_flutter_bloc_regressions_are_present(self):
        s = read("flutter/packages/growerp_catalog/test/product/product_bloc_test.dart")
        for phrase in ("CSV download returns file content without requesting email", "empty CSV fails closed", "API failure does not offer a CSV file"):
            self.assertIn(phrase, s)


if __name__ == "__main__":
    unittest.main()
