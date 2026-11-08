#!/usr/bin/env python3
"""Source-only, credential-free NASQO Google Business login contract checks."""
import json
import pathlib
import xml.etree.ElementTree as ET

BASE = pathlib.Path(__file__).resolve().parents[2]
backend_screen = BASE / "backend/screen/googleOAuth.xml"
backend_util = BASE / "backend/service/NasqoGoogleLoginUtil.groovy"
frontend = BASE / "flutter/packages/growerp_core/lib/src/domains/authenticate/views/login_dialog.dart"
bloc = BASE / "flutter/packages/growerp_core/lib/src/domains/authenticate/blocs/auth_bloc.dart"
admin_config = BASE / "flutter/packages/admin/assets/cfg/app_settings.json"
production_config = BASE / "deploy/nasqo/private/app_settings.json"
deploys = [BASE / "deploy/nasqo/compose.yaml", BASE / "deploy/nasqo/vpsdime/compose.yaml"]

screen = ET.parse(backend_screen).getroot()
transitions = [t.attrib.get("name") for t in screen.findall("transition")]
assert set(("callback", "loginStart", "loginCallback")).issubset(transitions)
assert transitions.count("callback") == 1  # Workspace flow preserved
assert set(("deviceCallback", "deviceApprove")).issubset(transitions)
util = backend_util.read_text()
for token in (
    "openid email", "code_challenge_method=S256", "nonce",
    "client_id", "GOOGLE_LOGIN_CLIENT_ID", "GOOGLE_LOGIN_CLIENT_SECRET",
    "NASQO_GOOGLE_LOGIN_ENABLED", "email_verified", "UserAuthcFactor",
    "UserPreference", "TenantSubscription", "requirePasswordChange",
    "disableAuthz", "internalLoginUser",
):
    assert token in util, f"Missing security or tenancy check: {token}"
assert "refresh_token" not in util.lower(), "Business login must not persist Google refresh tokens"
for token in ("startDevice", "completeDeviceCallback", "approveDevice",
              "pollDevice", "MAX_PAIRING_ATTEMPTS", "deviceCallbackUri",
              "MessageDigest.isEqual"):
    assert token in util, f"Missing native device pairing security: {token}"
device_services = BASE / "backend/service/growerp/100/NasqoGoogleDeviceLoginServices100.xml"
assert device_services.exists()
ET.parse(device_services)
rest = (BASE / "backend/service/growerp.rest.xml").read_text()
assert "GoogleDeviceLoginStart" in rest and "GoogleDeviceLoginPoll" in rest
native_client = (BASE / "flutter/packages/growerp_models/lib/src/rest_client/auth_api.dart").read_text()
assert "startGoogleDeviceLogin" in native_client and "pollGoogleDeviceLogin" in native_client
native_dialog = (BASE / "flutter/packages/growerp_core/lib/src/domains/authenticate/views/google_device_login_dialog.dart").read_text()
assert "openExternalUrl" in native_dialog and "_pairingCode" in native_dialog
assert "AuthGoogleDeviceLoginSucceeded" in bloc.read_text()
ui = frontend.read_text()
assert "googleLoginEnabled" in ui
assert "googleOAuth/loginStart" in ui
assert "kIsWeb" in ui
assert "'AppSupport'" in ui
assert "webOnlyWindowName: '_self'" in ui
bl = bloc.read_text()
assert "_consumeGoogleLoginRedirect" in bl
assert "getAuthenticate(" in bl
assert "authenticated.apiKey == null" in bl
for config in (admin_config, production_config):
    assert json.loads(config.read_text()).get("googleLoginEnabled") is False
for path in deploys:
    c = path.read_text()
    for key in ("NASQO_GOOGLE_LOGIN_ENABLED:", "GOOGLE_LOGIN_CLIENT_ID:",
                "GOOGLE_LOGIN_CLIENT_SECRET:", "GOOGLE_LOGIN_REDIRECT_URI:"):
        assert key in c, f"Missing {key} in {path.name}"
caddy = (BASE / "deploy/nasqo/vpsdime/patch_caddy.py").read_text()
assert "/googleOAuth*" in caddy
print("NASQO Google Business login source contract: PASS")
