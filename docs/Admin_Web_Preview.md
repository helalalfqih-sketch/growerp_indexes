# Admin web preview deployment

This project builds the existing Flutter Admin application from the repository,
rather than serving a mock UI. Vercel uses `ci/build-admin-web-vercel.sh`,
which pins Flutter 3.47.9, bootstraps the Melos workspace, generates source and
locale code, and produces a release Flutter web build in
`flutter/packages/admin/build/web`. `vercel.json` serves static assets first
and falls back to `index.html` for client-side routes.

The frontend currently targets the upstream GrowERP service:
`https://backend.growerp.com` for REST and `wss://backend.growerp.com`
for chat. **The upstream backend is not part of this preview deployment.**
Login and financial workflows require a running, authorized backend with
matching CORS/origin and authentication configuration. Avoid creating real
financial data or entering private business credentials in an unvalidated
preview. Configure a dedicated test Moqui deployment before use.

The workflow `.github/workflows/admin-web-preview.yml` separately verifies
that Flutter generates a nonempty web bundle and publishes a short-lived build
artifact; neither it nor the Vercel build updates GrowERP production services.

## Operational verification checklist

1. Verify the GitHub Actions web-build job and exact head commit SHA.
2. Inspect the Vercel deployment build logs and confirm the web assets exist.
3. Open the Vercel URL in a real browser, test deep links and language switching.
4. Inspect REST and WebSocket requests in the browser network console.
5. Run authentication, permissions and invoice workflows against an isolated
   backend before accepting this as a functioning accounting environment.

## GitHub Pages preview

The PR-scoped workflow additionally builds Admin with the repository base path
`/growerp_indexes/`, uploads a GitHub Pages artifact, and attempts to publish
that UI at the fork's Pages URL. Deployment is restricted to same-repository
PRs from `feat/admin-web-vercel-preview`. Pages must be enabled/authorized
for Actions on the GitHub repository; a successful build alone does not prove
that GitHub Pages accepted the deployment.

The Vercel script builds for root (/) separately; its configuration remains
available, but linking the Git provider to Vercel currently requires account
authorization. Both hosting choices only publish frontend assets, not Moqui.
