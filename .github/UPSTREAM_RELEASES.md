# Integrating OpenEMS releases

The **Integrate OpenEMS Release** workflow creates a pull request that brings a
published OpenEMS release into NFE `main` without rewriting NFE history.

## Repository setup

1. Install a GitHub App on `Nearly-Free-Energy/openems` with read access to
   metadata, read access to packages, and read/write access to contents, issues,
   pull requests, and workflows. Workflow access is required because upstream
   release branches contain files below `.github/workflows`.
2. Add the App ID as the `NFE_RELEASE_APP_ID` Actions secret and its private key
   as `NFE_RELEASE_APP_PRIVATE_KEY`.
3. Keep the existing `main` pull-request checks. Do not make the four Docker
   matrix jobs required branch checks: they run only for `upstream-release/*`
   pull requests and are validated again before image publication.
4. Merge upstream integration pull requests with a merge commit. The publisher
   verifies that the upstream release remains in the merged commit's ancestry;
   squash or rebase merges therefore do not publish images.

## Monthly procedure

1. Open **Actions > Integrate OpenEMS Release > Run workflow**.
2. Enter the published upstream tag (for example `2026.8.0`) and a unique NFE
   image tag (for example `nfe-v0.2.0`).
3. Review the generated `upstream-release` pull request. If it conflicts, merge
   current NFE `main` into its integration branch and resolve each conflict while
   retaining NFE-specific behavior.
4. Wait for Java, UI, and Docker validation, approve the pull request, and merge
   it using a merge commit.

The merged pull request automatically reruns all validation against the exact
merge commit. Only then are the four images published to
`ghcr.io/nearly-free-energy`. Successful publication deletes the integration
branch. Failed or conflicted branches remain available for investigation.
