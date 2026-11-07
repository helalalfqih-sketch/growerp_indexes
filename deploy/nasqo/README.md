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
