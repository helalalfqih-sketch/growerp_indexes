/* NASQO accounting source review. Authenticated GET only; no import or posting actions. */
(function () {
  'use strict';
  const endpoint = '/rest/s1/growerp/100/SqliteImport/Review';
  const routeMatches = hash => /^#\/acct-setup(?:[/?]|$)/.test(hash || '');
  function parseApiKey(raw) {
    if (!raw) return null;
    try { const key = JSON.parse(raw); return typeof key === 'string' && key.length ? key : null; }
    catch (_) { return null; }
  }
  function project(payload) {
    const r = payload && payload.review;
    if (!r || typeof r.available !== 'boolean') throw new Error('تعذّر قراءة نتيجة المراجعة.');
    if (!r.available) return { available: false, status: r.status };
    if (r.status !== 'REVIEW_ONLY_NOT_IMPORTED' || r.readOnly !== true ||
        r.readyForAccountingImport !== false || r.financialDocumentsCreated !== 0 || r.postedEntries !== 0) {
      throw new Error('حالة المراجعة غير متوافقة. حدّث الصفحة وأعد المحاولة.');
    }
    return r;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { routeMatches, parseApiKey, project, endpoint };
    return;
  }
  if (document.getElementById('nasqo-accounting-review-open')) return;
  const style = document.createElement('style');
  style.textContent = `
    #nasqo-accounting-review-open{position:fixed;left:16px;bottom:78px;z-index:2147483646;border:1px solid #006d58;border-radius:24px;padding:12px 18px;background:#fff;color:#006d58;font:600 14px system-ui;box-shadow:0 3px 14px #0002;cursor:pointer}
    #nasqo-accounting-review-open[hidden]{display:none}
    #nasqo-accounting-review{box-sizing:border-box;width:min(980px,calc(100vw - 24px));max-height:90vh;max-height:90dvh;border:1px solid #c6dfd6;border-radius:20px;padding:0;color:#163c33;background:#f7fbf9;font:15px/1.65 system-ui,sans-serif;direction:rtl;box-shadow:0 18px 90px #0005}
    #nasqo-accounting-review::backdrop{background:#102f28a8}
    #nasqo-accounting-review header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding:22px 24px;border-bottom:1px solid #dcebe5;background:#e8fff4}
    #nasqo-accounting-review h2{font-size:22px;margin:0}
    #nasqo-accounting-review h3{font-size:17px;margin:22px 0 8px}
    #nasqo-accounting-review p{margin:6px 0}
    #nasqo-accounting-review .review-body{padding:20px 24px}
    #nasqo-accounting-review button{font:inherit;cursor:pointer}
    #nasqo-accounting-review .close-review{border:1px solid #83b5a6;border-radius:10px;padding:5px 13px;background:#fff;color:#075844}
    #nasqo-accounting-review .reload-review{border:1px solid #006d58;border-radius:10px;padding:8px 14px;background:#006d58;color:#fff;margin-top:12px}
    #nasqo-accounting-review .review-stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(125px,1fr));gap:10px;margin:18px 0}
    #nasqo-accounting-review .review-stat{padding:14px;border:1px solid #dae8e2;border-radius:12px;background:#fff}
    #nasqo-accounting-review .review-stat strong{display:block;font-size:25px;font-variant-numeric:tabular-nums}
    #nasqo-accounting-review .review-note{border-right:4px solid #d99c27;background:#fff4d9;padding:12px 15px;border-radius:8px}
    #nasqo-accounting-review .review-table{overflow:auto;border:1px solid #dbe8e1;border-radius:10px;background:#fff}
    #nasqo-accounting-review table{width:100%;border-collapse:collapse;white-space:nowrap;text-align:right}
    #nasqo-accounting-review th,#nasqo-accounting-review td{padding:10px 12px;border-bottom:1px solid #edf2ef}
    #nasqo-accounting-review th{background:#edf7f1;font-weight:600}
    #nasqo-accounting-review .review-exception{background:#fff2db}
    #nasqo-accounting-review .review-tags{display:flex;flex-wrap:wrap;gap:8px}
    #nasqo-accounting-review .review-tag{border:1px solid #cee4d9;padding:5px 10px;background:#fff;border-radius:8px}
    #nasqo-accounting-review .review-muted{color:#566c63;font-size:13px}
    #nasqo-accounting-review button:focus-visible,#nasqo-accounting-review-open:focus-visible{outline:3px solid #e4af36;outline-offset:3px}
    @media(max-width:600px){#nasqo-accounting-review header,#nasqo-accounting-review .review-body{padding:16px}#nasqo-accounting-review h2{font-size:19px}#nasqo-accounting-review-open{max-width:calc(100vw - 32px)}}
  `;
  document.head.append(style);
  const make = (tag, text, cls) => {
    const e = document.createElement(tag);
    if (text != null) e.textContent = String(text);
    if (cls) e.className = cls;
    return e;
  };
  const trigger = make('button', 'عرض المراجعة المحفوظة');
  trigger.id = 'nasqo-accounting-review-open';
  trigger.type = 'button';
  trigger.dir = 'rtl';
  trigger.setAttribute('aria-haspopup', 'dialog');
  const dialog = make('dialog');
  dialog.id = 'nasqo-accounting-review';
  dialog.lang = 'ar';
  dialog.setAttribute('aria-labelledby', 'nasqo-accounting-review-title');
  const header = make('header');
  const heading = make('div');
  const title = make('h2', 'مراجعة المحاسب الذكي برو');
  title.id = 'nasqo-accounting-review-title';
  heading.append(title, make('p', 'بيانات محفوظة للمراجعة المحاسبية', 'review-muted'));
  const close = make('button', 'إغلاق', 'close-review');
  close.type = 'button';
  header.append(heading, close);
  const body = make('div', null, 'review-body');
  body.setAttribute('aria-live', 'polite');
  dialog.append(header, body);
  document.body.append(trigger, dialog);
  let controller = null;
  let sessionGuard = null;
  let generation = 0;
  const storedSession = () => {
    try { return window.localStorage.getItem('flutter.apiKey'); }
    catch (_) { return null; }
  };
  const text = value => value == null ? '—' : String(value);
  function clearRequest() {
    generation++;
    if (controller) controller.abort();
    controller = null;
    if (sessionGuard) clearInterval(sessionGuard);
    sessionGuard = null;
  }
  function dismiss() {
    clearRequest();
    body.replaceChildren();
    if (dialog.open) dialog.close();
  }
  function table(section, labels, rows, highlight) {
    if (!rows.length) return;
    body.append(make('h3', section));
    const wrapper = make('div', null, 'review-table');
    const t = make('table');
    const head = make('thead');
    const tr = make('tr');
    labels.forEach(label => { const th = make('th', label); th.scope = 'col'; tr.append(th); });
    head.append(tr);
    const tb = make('tbody');
    rows.slice(0, 500).forEach((cells, index) => {
      const row = make('tr', null, highlight && highlight(index) ? 'review-exception' : '');
      cells.forEach(value => row.append(make('td', text(value))));
      tb.append(row);
    });
    t.append(head, tb); wrapper.append(t); body.append(wrapper);
  }
  function render(r) {
    body.replaceChildren();
    if (!r.available) {
      body.append(make('p', r.status === 'RECONCILIATION_PENDING'
        ? 'النسخة محفوظة، وتقرير المطابقة لم يكتمل بعد.'
        : 'لا توجد مراجعة محفوظة مرتبطة بشركتك الحالية.'));
      return;
    }
    body.append(make('p', 'الشركة: ' + text(r.companyPartyId) + ' · مرجع النسخة: ' + text(r.sourceReference), 'review-muted'));
    body.append(make('p', 'لم تُستورد المستندات أو الأرصدة إلى المحاسبة بعد. النتائج أدناه للمراجعة قبل الاستيراد.', 'review-note'));
    const s = r.summary || {};
    const stats = make('div', null, 'review-stats');
    [['حركة في المصدر', s.movements], ['حساب في المصدر', s.sourceAccounts],
     ['صندوق نقدي', s.cashboxes], ['مجموعة حسابات', s.accountGroups],
     ['بند فاتورة', r.invoiceLines]].forEach(([label, value]) => {
      const card = make('div', null, 'review-stat');
      card.append(make('strong', typeof value === 'number' ? value.toLocaleString('en-US') : '—'), make('span', label));
      stats.append(card);
    });
    body.append(stats);
    body.append(make('h3', 'حالات تحتاج إلى مراجعة'));
    body.append(make('p', 'حركات بعملات غير معرّفة: ' + text(s.unknownCurrencyRows) +
      ' · أزواج تحويل غير متوازنة: ' + text(s.unbalancedTransferPairs) +
      ' · سجلات تحمل وصف رصيد سابق: ' + text(s.openingLabelRows)));
    const cashboxes = Array.isArray(r.cashboxes) ? r.cashboxes : [];
    table('الصناديق النقدية', ['رقم المصدر', 'الاسم', 'رمز العملة في المصدر'],
      cashboxes.map(c => [c.id, c.name, c.cur]));
    const exceptions = Array.isArray(r.currencyExceptions) ? r.currencyExceptions : [];
    table('حركات العملات غير المعرّفة', ['رقم الحركة', 'التاريخ', 'رمز العملة', 'عليه في المصدر', 'له في المصدر'],
      exceptions.map(x => [x.sourceId, x.date, x.sourceCurrencyId, x.rawDain, x.rawMadin]));
    const pairs = Array.isArray(r.transferPairs) ? r.transferPairs : [];
    table('أزواج التحويل المرشحة للمطابقة', ['الحركات', 'قيمة الطرف الأول', 'قيمة الطرف الثاني', 'الفرق', 'المراجعة'],
      pairs.map(p => [(p.sourceIds || []).join(' / '), p.dainAmount, p.madinAmount, p.difference,
        p.status === 'UNBALANCED_SOURCE_PAIR' ? 'يوجد فرق' :
        p.status === 'BALANCED_SOURCE_PAIR_CANDIDATE' ? 'متوازن — لم يُعتمد بعد' : 'يحتاج مطابقة']),
      i => pairs[i].status !== 'BALANCED_SOURCE_PAIR_CANDIDATE');
    const openings = Array.isArray(r.openingCandidates) ? r.openingCandidates : [];
    table('سجلات الرصيد السابق في المصدر', ['رقم الحركة', 'التاريخ', 'رمز العملة', 'عليه في المصدر', 'له في المصدر'],
      openings.map(x => [x.sourceId, x.date, x.sourceCurrencyId, x.rawDain, x.rawMadin]));
    body.append(make('h3', 'مجموعات الحسابات'));
    const tags = make('div', null, 'review-tags');
    (Array.isArray(r.groups) ? r.groups : []).slice(0, 500).forEach(g => tags.append(make('span', text(g.name), 'review-tag')));
    body.append(tags, make('p', 'المبالغ معروضة كما وردت في المصدر، ولا تمثل قيودًا مُرحّلة في نسقو.', 'review-muted'));
    const reload = make('button', 'تحديث المراجعة', 'reload-review');
    reload.type = 'button'; reload.addEventListener('click', loadReview); body.append(reload);
  }
  async function loadReview() {
    clearRequest();
    const requestGeneration = generation;
    const session = storedSession();
    body.replaceChildren(make('p', 'جارٍ تحميل المراجعة المحفوظة…'));
    const key = parseApiKey(session);
    if (!key) {
      body.replaceChildren(make('p', 'سجّل الدخول إلى نسقو بحساب مدير الشركة لعرض المراجعة.'));
      return;
    }
    controller = new AbortController();
    const activeController = controller;
    const timeout = setTimeout(() => activeController.abort(), 20000);
    sessionGuard = setInterval(() => {
      if (storedSession() !== session) {
        clearRequest();
        body.replaceChildren(make('p', 'تغيّرت جلسة الدخول. أغلق اللوحة وافتحها من حساب الشركة الحالية.'));
      }
    }, 500);
    try {
      const response = await fetch(endpoint, { method: 'GET', credentials: 'same-origin', cache: 'no-store',
        headers: { Accept: 'application/json', api_key: key }, signal: activeController.signal });
      if (requestGeneration !== generation || storedSession() !== session) return;
      if (response.status === 401 || response.status === 403) throw new Error('يتطلب عرض المراجعة صلاحية مدير الشركة. تحقّق من الحساب والشركة الحالية.');
      if (!response.ok) throw new Error('تعذّر تحميل المراجعة. حاول مجددًا بعد قليل.');
      const payload = await response.json();
      if (requestGeneration !== generation || storedSession() !== session) return;
      render(project(payload));
    } catch (error) {
      if (requestGeneration !== generation) return;
      body.replaceChildren(make('p', error.name === 'AbortError' ? 'انتهت مهلة التحميل. أغلق اللوحة وأعد المحاولة.' : error.message));
    } finally { clearTimeout(timeout); }
  }
  function syncRoute() {
    const visible = routeMatches(window.location.hash);
    trigger.hidden = !visible;
    if (!visible) dismiss();
  }
  trigger.addEventListener('click', () => { if (!dialog.open) dialog.showModal(); loadReview(); });
  close.addEventListener('click', dismiss);
  dialog.addEventListener('close', () => { clearRequest(); body.replaceChildren(); });
  dialog.addEventListener('cancel', () => { clearRequest(); body.replaceChildren(); });
  window.addEventListener('hashchange', syncRoute);
  window.addEventListener('pagehide', dismiss);
  syncRoute();
})();
