# NASQO ERP — production foundation

This folder is an **auditable deployment configuration**, **not proof of a completed production launch**. It is specific to `helalalfqih-sketch/growerp_indexes`. The source is pinned to `NASQO_COMMIT` and is intentionally separate from GrowERP's public backend.

## Architecture

- Flutter Admin: separately built from the checked-out **NASQO repo** with `deploy/nasqo/Dockerfile.web`.
- Moqui: built from the **NASQO repo** at the reviewed Git SHA, using Java 21.
- PostgreSQL 17: dedicated persistent named volume; no external TCP port.
- Caddy: TLS termination, same-origin routing for web, REST, and WebSocket.
- `backend` always has `DB_DATA=NONE`; demo loading cannot be triggered by routine startup.
- One-time `bootstrap` loads `seed,seed-initial,install`, **not** `demo`. These are required system/reference/configuration records and must not be confused with business demo records.

## Before any public deployment

1. Provision a **durable** Linux server with sufficient resources, a real domain, DNS, backups, and monitoring. Codespaces is **not** durable hosting. Confirm hosting costs first.
2. Configure DNS and inbound 80/443. Ensure PostgreSQL 5432 is NOT publicly reachable.
3. Copy `.env.example` to `.env` in this directory. Fill real domain, a reviewed master commit, tag, and two unique high-entropy secrets. `.env` is gitignored; do not paste passwords into ChatGPT.
4. Check out exactly the audited `NASQO_COMMIT` in the source tree before building: `git rev-parse HEAD` must match it. Source env: `set -a; . ./.env; set +a`. Generate frontend runtime settings with `python3 prepare_config.py`.
5. Run `docker compose --env-file .env -f compose.yaml config --quiet`, then `docker compose --env-file .env -f compose.yaml build backend web`. Builds MUST complete successfully before proceeding.
6. First installation only, with a **new empty DB**: `docker compose --env-file .env -f compose.yaml --profile bootstrap run --rm bootstrap`. This initializes required system tables/security/reference configuration; it does **not** request `demo` records.
7. Run `docker compose --env-file .env -f compose.yaml up -d`. Verify healthy services, HTTPS, app authentication, REST and chat. Create the initial administrator **privately through the authenticated onboarding/admin bootstrap flow**, then remove or disable any default credentials if the framework creates them.
8. Verify tenant isolation, RBAC, financial audit behavior, backup **and restore**, security headers, password-reset email, CORS, data encryption key retention, log redaction, and staging-to-production rollback before accepting real customers.

## Important gates and known limitations

- **Do not go live** until the bundled Moqui image build, init load, and login workflow have been validated on a clean disposable infrastructure instance with system records only. No fictitious customers, products, invoices, or sales may be created.
- Never start the original `docker/docker-compose.yaml` for NASQO production: it uses GrowERP domains, example credentials, `instance_purpose=test`, wildcard origins, and upstream imagery.
- `Dockerfile.moqui` is pinned to your Git SHA but its backend build has not yet been validated in a full clean container build.
- The default Flutter entrypoint historically calls `backend.growerp.com` for a backend version check. It must use the configured origin from `app_settings.json`; the NASQO change is tracked alongside these files.
- Configure a working transactional mail provider for password delivery and account recovery, or use another reviewed, secure initial-admin bootstrap flow. **Never share passwords or API keys in the repository or chat.**
- `NASQO_CRYPT_PASSWORD` encrypts persistent database fields; back up the secret securely, separately from DB backups.
- The deployment uses automatic HTTPS when the domain is real and ports 80/443 are reachable.
- It does not provision a paid hosting plan or configure a domain automatically.
- `bootstrap` should be executed exactly for first initialization and never against an existing unknown database.
- Avoid empty placeholders or seeded business/demo data. Reference/security/application configuration required to operate Moqui is allowed.
- Check any lingering GrowERP branding, store package identities, product terms, and default user flows before release.

## Agent handoff — 2026-11-07

The production foundation was reviewed without connecting to a database or changing
business data. The automated checks passed for:

- no demo or business-data loader in routine production startup;
- PostgreSQL remaining private to the internal Docker network;
- production source being pinned to an audited Git SHA;
- the frontend using the configured NASQO backend origin instead of the GrowERP
  backend; and
- required production secrets remaining unset in the committed example file.

This handoff does not claim that production has been launched. Before the next
agent deploys anything, it must provision a durable NASQO host, create a private
`.env`, validate the pinned build, initialize only a new empty database, and
complete the readiness gates above. Do not use the untracked `system root` file
or any local preview credentials as production configuration.

## Arabic localization handoff — 2026-11-07

The Arabic resource for `growerp_sales` was completed and includes all 76
user-facing keys from its English template, including every placeholder used by
error, search, and opportunity messages. No database, API contract, or business
record was changed. The remaining packages still require Arabic resources before
the product can be described as fully Arabic.

The Arabic resource for `growerp_hr` was then completed with all 51
user-facing keys, including the employee-delete placeholder. This remains a
localization-only change and does not alter HR records or backend services.

The Arabic resource for `growerp_marketing` was then completed with all
user-facing keys from its English template, including count placeholders and
marketing guide statuses. No credentials, content records, or API behavior was
changed.

The courses Arabic resource is now complete with all 410 user-facing keys from
the English template, including course management, exercises, quizzes, media,
certificates, learner management, scheduling, and AI actions. Placeholder
references were validated, and no database, API contract, or course record was
changed.

## Error localization handoff — 2026-11-07

Common client-side network and session errors now use Core localization keys
instead of fixed English text. Arabic and English resources cover connection
failures, timeouts, cancellation, invalid data, server errors, sign-out, login
key expiry, and error codes. The generated Flutter localization files must be
regenerated with `flutter gen-l10n` before building the production web or
Android artifacts. Backend service messages remain a separate localization
surface and are not all translated by this change.

## Public verification note — 2026-11-07

From the development environment, `https://erp.indexesstore.com/` returned
`HTTP 200` over HTTP/2. This confirms only that the public web endpoint served
the page at that time; it does not independently prove Moqui health, PostgreSQL
health, backup restoration, account security, or that the new Caddy HSTS rule
has been deployed. Apply the Caddy change and run the production backup,
restore, and service checks on the VPS before declaring the launch complete.
