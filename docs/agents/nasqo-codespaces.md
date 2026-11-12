# NASQO development in GitHub Codespaces and dot

This guide applies to **helalalfqih-sketch/growerp_indexes** (Flutter + Moqui).
**nasqo-integration** is currently an unrelated Apify Actor template, not the ERP.

## Scope and safety

- This is a **development** container. It does **not** deploy to production,
  run database resets, load demo/seed data, or sign/publish APKs.
- The pinned Flutter SDK is **3.47.9** (same as NASQO Android CI).
- Java 21 runs Moqui; Android Gradle Plugin targets Java 17 bytecode.
  Use the repo's Gradle wrappers: Android uses Gradle 9.1.0, AGP 9.0.1;
  Moqui uses its own wrapper after submodule checkout.
- The container includes Docker-in-Docker for development, Android command-line
  tools (not the platform SDK), Python, Node.js and Flutter. SDK licensing is
  deliberately left to the user; do not auto-accept agreements.
- Codespaces is a **development environment, not durable production hosting**.
  Store source and configuration in Git. Never commit credentials, a signing
  keystore, an .env, database dumps, or device authentication files.

## Open the Codespace

1. In GitHub, open **Code > Codespaces** for this repository; select the
   reviewed branch containing `.devcontainer/devcontainer.json`.
2. Choose a machine with sufficient RAM/disk (prefer **8 cores / 16 GB RAM** or
   more when running Flutter + Moqui + Docker together; availability and billing
   depend on the account). Open in the browser to avoid local IDE installs.
3. Wait for the development container to finish building and for
   `.devcontainer/post-create.sh` to finish. It installs Melos.
4. In the Codespaces terminal, run:
   ```bash
   bash .devcontainer/bootstrap.sh check
   ```
   The command verifies Flutter, Java, Python, Node, SDK tools, Docker Engine
   and Docker Compose. A failure is actionable; don't claim success until it passes.

## Fetch and test source code

The repository is a multi-package Flutter/Melos workspace. Avoid running a
full backend + Flutter monorepo build automatically on each Codespace startup.

```bash
# Flutter dependencies (first run can be slow)
bash .devcontainer/bootstrap.sh flutter
cd flutter
melos analyze
melos run test_unit
```

For Moqui (separate step, may fetch many submodules):

```bash
cd /workspaces/growerp_indexes # or the folder containing this repository
bash .devcontainer/bootstrap.sh backend
cd moqui
./gradlew --version
# ./gradlew build      # only when ready for a heavier build
```

To build Android, **first** review and accept the Android SDK licenses
interactively if you agree with them:

```bash
sdkmanager --licenses
bash .devcontainer/bootstrap.sh android
bash .devcontainer/bootstrap.sh flutter
cd flutter/packages/admin
flutter build apk --debug --no-pub
```

For release APK, use the existing **NASQO Android release** GitHub Actions
workflow and its verification artifacts; signing keys remain outside Codespaces.
For the development backend, use the repo's `docs/agents/dev-setup.md` and
`docker/docker-compose-dev.yaml`; **do not** run
`deploy/nasqo/compose.yaml` as a development shortcut.

## Access from dot via Remote Desktop Commander

Dot is a lightweight control terminal, not the Docker host. Once GitHub CLI
is authorized on dot, list and enter an active Codespace:

```bash
gh auth login --hostname github.com --git-protocol https --scopes codespace
gh codespace list
gh codespace ssh -c CODESPACE_NAME
```

The login uses the user's GitHub device authorization; do not paste codes or
tokens into repositories. The dot device can run remote commands through the
Codespace's SSH session after authorization. Keep forwarded development ports
private; port 8080 is only Moqui's development service.

## Capacity and cost

Codespaces usage consumes compute time and storage. Stop the Codespace when
idle and review GitHub's Codespaces billing/quota. Run emulator-based Android
tests using the existing GitHub Actions workflow, rather than requiring an
emulator on a low-power local computer.

## Acceptance checklist (run in an actual Codespace)

- [ ] Dev Container builds successfully.
- [ ] `bootstrap.sh check` reports working Docker daemon and tool versions.
- [ ] `bootstrap.sh flutter` completes.
- [ ] `melos analyze` and `melos run test_unit` pass, or failures are recorded.
- [ ] `bootstrap.sh backend` + `moqui/gradlew --version` complete.
- [ ] Android license acceptance and SDK platform install are completed by
      the user when Android builds are needed.
- [ ] A debug APK builds, or the exact failure is recorded.
- [ ] Dot GitHub CLI is authorized and SSH into Codespaces succeeds.

These checks **require a live Codespace**; static config validation alone is not
proof that the container, Docker daemon, Flutter tests, or APK build work.
