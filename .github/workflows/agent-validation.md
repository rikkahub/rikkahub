# Agent Validation Workflow

`agent-validation.yml` is manual-only and checks out the exact `ref` supplied at dispatch. It uses no repository secrets and creates a disposable, dummy `app/google-services.json` on the GitHub-hosted runner.

The `verify` job is blocking: Kotlin compilation, third-batch targeted JVM tests, the full `app` and `search` JVM suites, platform-independent workspace tests, and `:app:assembleDebug` run in order. Workspace coverage intentionally excludes tests that need `/bin/sh` or symbolic-link creation permissions.

`lint` always runs separately after `verify`. Existing findings are non-blocking only when a lint task produces a report; configuration, dependency, or other Gradle task failures block the job. Its reports are always uploaded for review.

Room schemas under `app/schemas/**` are uploaded after the compilation step even if compilation fails, so the generated v28 schema can be downloaded from the workflow artifacts.

Run the workflow from the fork `ningbainb/rikkahub`. The first run requires committing and pushing this workflow to the branch that will host the workflow definition. Then dispatch it with:

```bash
gh workflow run agent-validation.yml --repo ningbainb/rikkahub --ref agent/third-batch-context-orchestration -f ref=agent/third-batch-context-orchestration
```
