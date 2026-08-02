# Agent Validation Workflow

`agent-validation.yml` runs for pull requests targeting the fork's `master` branch and for manual dispatches. Pull-request runs check out the immutable PR head SHA; dispatched runs check out the exact `ref` supplied as input. It uses only `contents: read`, no repository secrets, and creates a disposable, dummy `app/google-services.json` on the GitHub-hosted runner.

The `verify` job is blocking: Kotlin compilation, third-batch targeted JVM tests, the full `app` and `search` JVM suites, platform-independent workspace tests, and `:app:assembleDebug` run in order. The third-batch steps run only their named test classes; a failure in the later full `app` or `search` suites is reported as a suite failure rather than a third-batch regression. Workspace coverage intentionally excludes tests that need `/bin/sh` or symbolic-link creation permissions.

`lint` always runs separately after `verify`. Existing findings are non-blocking only when a lint task produces a report; configuration, dependency, or other Gradle task failures block the job. Its reports are always uploaded for review.

Room schemas under `app/schemas/**` are uploaded after the compilation step even if compilation fails, so the generated v28 schema can be downloaded from the workflow artifacts. Artifact names include the trigger type and run number, while concurrency is scoped by trigger type and the validated PR SHA or dispatch ref.

Push the workflow to the fork `ningbainb/rikkahub`, then open a pull request into `master` to run it automatically. GitHub does not schedule regular `pull_request` runs while a PR is a draft, so mark a newly created draft ready for review to schedule validation; it can be returned to draft after the run is queued. A workflow-dispatch run can validate another ref with:

```bash
gh workflow run agent-validation.yml --repo ningbainb/rikkahub --ref agent/third-batch-context-orchestration -f ref=agent/third-batch-context-orchestration
```
