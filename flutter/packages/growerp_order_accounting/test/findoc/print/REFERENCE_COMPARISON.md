# NASQO Arabic invoice — reference comparison

Scope: source-level comparison with the user-approved A4 portrait Arabic
credit-purchase invoice reference. The actual reference bitmap is not stored
in this Git branch, so exact pixel matching cannot be claimed.

| Reference element | Implementation status | Follow-up |
| --- | --- | --- |
| White A4 portrait with narrow margins | Implemented using caller page format and 24pt margins | Enforce A4 in approved template; PDF print audit |
| English company at top-left | Optional verifiedCompanyEnglishName | No bilingual field in Company |
| Centered company logo | Optional company.image in centered Row child | Check visual centering and missing-logo alignment |
| Arabic company at top-right | Company.name with Arabic text | Verify on PDF |
| Phone/address | Company.telephoneNr and verified Address parts | Verify LTR phone and address wrapping |
| Royal blue 'فاتورة شراء آجل' band | Implemented, #2953AF | Visual alignment audit |
| Horizontal separator | Added dark divider above and below metadata | Visual audit |
| Date and time | FinDoc.creationDate | Verify timezone policy; no timezone invention |
| Invoice number red | FinDoc.pseudoId/invoiceId, red | Verify LTR |
| Main warehouse | Only explicit verifiedWarehouseName | Backend facility mapping not complete |
| Supplier highlighted red | Party name currently dark; source known | Optional styling; ensure correct supplier identity |
| Notes | FinDoc.description | Wrap long Arabic text |
| Seven RTL columns | Implemented: index, name, expiry, unit, qty, price, amount | Verify physical order and header repetition |
| Expiry | Empty unless verified lot source supplied | Backend lot integration pending |
| Unit | Empty unless verified sale UOM supplied | Backend UOM integration pending |
| Thin dark table grid | Implemented #444444 0.5pt | Visual audit |
| Blue numeric values | Implemented blue for numeric columns | Verify visual contrast and Arabic shaping |
| Five example rows | Synthetic reference test values 10,2,1,1,1 | Source item descriptions are not hardcoded |
| Total quantity 15 | Added only when every line has the same verified sale UOM | Current FinDoc mapping lacks verified sale UOM, so hidden |
| Net total 63,000 yellow | Authoritative FinDoc.grandTotal on #FFFBC2 | Verify actual currency, not company default |
| Arabic words in red | Optional verifiedAmountInWords | Real amount-to-words implementation pending |
| Balance 0 | Omitted: meaning/accounting source not verified | Determine invoice vs party balance |
| Return terms | Optional returnPolicy | Must be company-specific |
| Signatures seller/warehouse/cashier/recipient | Implemented | Visual spacing audit |
| Multi-page repeated table header | headerCount=1 | Runtime PDF inspection pending |
| RTL Arabic + LTR invoice/phone/amount | Explicit directions | Rendering/Bidi visual review pending |

## Engineering notes

- No fabricated warehouse, expiry, unit, words, or balance.
- Do not print amount words unless verified against the authoritative total and currency.
- A summed quantity across different units may be misleading; validate compatible UOM
  before activating the quantity total for real invoices.
- PDF layout tests and pixel comparisons have not run: no Flutter SDK on the server.
- Production is untouched. Keep NASQO_AR_INVOICE_PDF disabled until tests pass.
