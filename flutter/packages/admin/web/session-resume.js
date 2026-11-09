/* Restore an existing NASQO session on reload; the backend still validates it. */
(function () {
  'use strict';
  function hasSavedSession(rawAuth, rawKey) {
    try {
      const serialized = JSON.parse(rawAuth);
      const auth = typeof serialized === 'string' ? JSON.parse(serialized) : null;
      const key = JSON.parse(rawKey);
      return typeof key === 'string' && key.length > 0 &&
        key !== 'registered' && auth && auth.apiKey === key &&
        typeof auth.user?.userId === 'string' && auth.user.userId.length > 0;
    } catch (_) { return false; }
  }
  function resumeSavedSession(win) {
    try {
      const url = new URL(win.location.href);
      if (url.searchParams.get('googleLogin') !== 'success') return false;
      const navigation = win.performance.getEntriesByType('navigation')[0];
      // A fresh Google callback must still replace any previous account.
      if (!navigation || !['reload', 'back_forward'].includes(navigation.type)) return false;
      if (!hasSavedSession(win.localStorage.getItem('flutter.authenticate'),
                           win.localStorage.getItem('flutter.apiKey'))) return false;
      url.searchParams.delete('googleLogin');
      // Preserve the Flutter route, other query fields and router history state.
      win.history.replaceState(win.history.state, '', url.pathname + url.search + url.hash);
      return true;
    } catch (_) {
      // Storage/history restrictions must not break the normal sign-in flow.
      return false;
    }
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { hasSavedSession, resumeSavedSession };
    return;
  }
  resumeSavedSession(window);
})();
