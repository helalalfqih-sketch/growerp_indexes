# تسجيل الدخول عبر Google إلى نسقو — NASQO Business

## النطاق والحالة

هذه ميزة جديدة لتسجيل دخول **مستخدمي نظام نسقو الموجودين مسبقًا** باستخدام حساب Google على واجهة **الويب فقط**. ليست هي نافذة «ربط Google Workspace» التي تسمح بقراءة Drive أو Gmail أو التقويم.

**الحالة الحالية: منفذة في الشيفرة، معطلة افتراضيًا، وغير منشورة للإنتاج.** لا يمكن اختبار تدفق Google كاملًا دون عميل OAuth مهيأ وتجربة على بيئة Staging. لا تُشغِّلها على الإنتاج قبل اختبارات الأمان والقبول.

## طريقة عملها

1. يظهر زر «المتابعة باستخدام Google» في نافذة تسجيل الدخول عند تفعيل `googleLoginEnabled`، ولغير تطبيق الدعم الفني فقط.
2. يفتح الويب `/googleOAuth/loginStart` في **نفس التبويب**.
3. ينشئ Moqui حالة عشوائية مرتبطة بجلسة المتصفح + قيمة `nonce` + مفتاح PKCE، مع مهلة 10 دقائق.
4. يوجّه Google المستخدم للموافقة على نطاقي `openid email` فقط، دون طلب خدمات Workspace.
5. يعيد Google إلى `/googleOAuth/loginCallback`، حيث يستبدل الخادم رمز التفويض، ويتحقق من `id_token` عبر Google HTTPS tokeninfo وتفاصيل `aud`, `azp`, `iss`, `exp`, `iat`, `nonce`, `sub`, `email_verified` والنطاق المستضاف.
6. يطابق الخادم البريد مع **حساب موجود ومحدد بشكل فريد**، ويتحقق من عدم تعطيله وعدم طلب تغيير كلمة المرور، ويمنع حسابات الدعم الفني والحسابات التي تتطلب عوامل تحقق إضافية.
7. يتحقق من اكتمال إعداد الشركة وصلاحية الاشتراك، ويربط مُعرّف Google الثابت `sub` بمفتاح `nasqo.google.subject` داخل `moqui.security.UserPreference` عند أول تسجيل ناجح. لا ينشئ مستخدمًا أو شركة أو صلاحية جديدة.
8. ينشئ جلسة Moqui ثم يعيد إلى `/?googleLogin=success` دون وضع رمز API أو كلمة مرور أو رمز Google في الرابط.
9. تطلب Flutter `get#Authenticate` عبر الجلسة نفسها، ولا تعتمد الدخول إلا عند استلام مستخدم ومفتاح API صالحين.

**حدود البريد:** يقتصر على Gmail أو نطاق Google Workspace الذي يثبت ادعاء `hd` أنه يستضيفه. حسابات البريد الخارجي غير المستضاف لدى Google لا تستطيع الدخول بهذا المسار.

## إنشاء بيانات Google Cloud

أنشئ OAuth Client منفصلًا من نوع **Web application** لمستخدمي نسقو، حتى لا تتداخل صلاحيات Workspace أو Firebase.

- Authorized JavaScript origin (إن طلبه Google): `https://erp.indexesstore.com`
- Authorized redirect URI: `https://erp.indexesstore.com/googleOAuth/loginCallback`
- النطاقات المطلوبة: `openid` و`email` فقط.

**تنبيه:** العنوان `https://smartcontentcreator2.firebaseapp.com/__/auth/handler` يخص Firebase ولا يحل محل عنوان نسقو أعلاه.

### متغيرات الخادم المحمية

ضع القيم في ملف `.env` الخاص بالخادم وليس في Flutter أو GitHub أو المحادثة:

```ini
NASQO_GOOGLE_LOGIN_ENABLED=true
GOOGLE_LOGIN_CLIENT_ID=<العميل المستقل لنظام نسقو>
GOOGLE_LOGIN_CLIENT_SECRET=<القيمة السرية داخل ملف محمي فقط>
GOOGLE_LOGIN_REDIRECT_URI=https://erp.indexesstore.com/googleOAuth/loginCallback
```

متغيرات `GOOGLE_OAUTH_CLIENT_ID` و`GOOGLE_OAUTH_CLIENT_SECRET` القديمة خاصة بربط خدمات Workspace ولا تُستخدم هنا.

### تفعيل زر واجهة الويب

بعد نشر بناء Flutter المراجع، غيّر `googleLoginEnabled` إلى `true` في إعدادات `app_settings.json` الخاصة بالإصدار الجاري؛ القيمة الافتراضية الآن `false`. لا تفعّل الزر إذا كانت الخدمة الخلفية أو Google Cloud غير جاهزة. يلزم إعادة تحميل تطبيق الويب بعد نشر الشيفرة المُترجمة من المصدر.

**لا تكتفِ بتعديل تطبيق الواجهة القديم الموجود في Nginx:** أعد بناء الواجهة من الشيفرة المراجعة واحرص على أن يتضمن ملف JavaScript الجديد كل الإصلاحات العربية والهوية الحالية.

## التوجيه

يستقبل Caddy `/googleOAuth*` في Moqui. عُدّل `deploy/nasqo/vpsdime/patch_caddy.py` ليشمل هذا المسار حتى عند إعادة تشغيل أداة النشر على إعداد Caddy قديم. لا تُوجِّه هذه المسارات إلى Flutter؛ ستتحول صفحات OAuth إلى الصفحة الرئيسية ولن يكتمل الدخول.

## الاختبارات المطلوبة قبل نشر الإنتاج

- نجاح `dart analyze` لملفي LoginDialog وAuthBloc.
- نجاح فحص XML في `backend/screen/googleOAuth.xml`، وتحويل Groovy.
- نجاح اختبار ادعاءات هوية Google المحلي دون أسرار أو طلبات شبكة:
  `backend/src/test/groovy/NasqoGoogleLoginPolicyUnit.groovy`.
- نجاح اختبار مكوّن OAuth محليًا: الحالة والـPKCE وربط الجلسة ومرة الاستخدام الواحدة.
- على Staging: دخول مستخدم موجود من Gmail ثم تسجيل الخروج، وإعادة تسجيل الدخول من متصفح ثانٍ.
- فشل الدخول ببريد غير مسجل، ومع `email_verified=false`، ومع اختلاف `aud` أو `nonce` أو انتهاء حالة الجلسة أو محاولة إعادة استخدامها.
- منع الدعم الفني والحسابات ذات MFA والحسابات المعطلة أو التي يجب تغيير كلمة مرورها.
- اختبار حالة الاشتراك المنتهي وإعداد الشركة غير المكتمل، وعدم تغيير أي أدوار.
- التحقق من أن `session` و`CSRF` يعملان عبر البروكسي وأن Google OAuth لا يعطّل مسار Workspace القديم `/googleOAuth/callback`.
- Android وiOS **ليسا داخل نطاق هذه الإضافة**؛ يحتاجان مسار OAuth محمولًا واختبارات app links أو Google SDK مستقلة.

## التراجع

اضبط `NASQO_GOOGLE_LOGIN_ENABLED=false` على الخادم و`googleLoginEnabled=false` في تطبيق Flutter؛ تظل كلمة المرور القديمة متاحة. لا تحذف تفضيلات ربط `sub` أو أي حساب مستخدم أثناء الرجوع.

## الملفات الرئيسية

- `backend/service/NasqoGoogleLoginUtil.groovy` — تبادل الرموز وتحقيق ادعاءات OIDC وربط الحسابات.
- `backend/screen/googleOAuth.xml` — مسارا `loginStart` و`loginCallback`.
- `flutter/packages/growerp_core/lib/src/domains/authenticate/views/login_dialog.dart` — زر ويب Google.
- `flutter/packages/growerp_core/lib/src/domains/authenticate/blocs/auth_bloc.dart` — استلام الجلسة بعد عودة Google.
- `flutter/packages/admin/assets/cfg/app_settings.json` و`deploy/nasqo/private/app_settings.json` — مفاتيح إظهار زر الويب.
- `deploy/nasqo/compose.yaml` و`deploy/nasqo/vpsdime/compose.yaml` و`deploy/nasqo/.env.example` — تعريفات متغيرات الخادم فقط.

**لا ينبغي الاعتماد على اختبار نجاح الترجمة البرمجية وحده. يجب إجراء اختبارات OAuth فعلية في بيئة معزولة قبل النشر.**
