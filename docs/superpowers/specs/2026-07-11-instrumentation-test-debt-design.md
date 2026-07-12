# Instrumentation Test Debt Repair Design

## Goal

Make the repository-wide debug instrumentation suite compile and pass on the connected Galaxy S23 FE without weakening meaningful coverage or changing production behavior.

## Scope

The repair covers four independently reproduced test defects:

1. The app context test hard-codes the release application ID even though the debug variant adds `.debug`.
2. The `highlight` module retains a generated placeholder instrumentation test but declares no instrumentation-test dependencies.
3. The migration test deliberately preserves an oversized `nodes` value, then loads that same value through Samsung's bounded `CursorWindow` while verifying the result.
4. The voice status-card test uses the experimental Compose JUnit v2 rule, which does not provide the activity-hosted hierarchy required by this device test.

Production database migration, UI, and application code are outside scope unless test-first evidence reveals an additional production defect.

## Design

### Variant-aware package assertion

Keep the app context wiring test, but compare the target context package with the generated application ID for the tested variant. This validates instrumentation targeting for both debug and release variants without duplicating build configuration in the test.

### Remove the stale highlight placeholder

Delete `highlight`'s generated `ExampleInstrumentedTest`. It only asserts a generated package name and tests no highlighting behavior. Adding AndroidX/JUnit dependencies solely for this placeholder would increase build surface without useful coverage.

### CursorWindow-safe migration verification

Keep creating a conversation large enough to exercise the migration's `SQLiteBlobTooBigException` path. After migration, verify whether the field was cleared or preserved using scalar SQL metadata such as `length(nodes)` rather than returning the oversized text into a cursor window. Continue verifying that the normal conversation migrates and that both conversation rows remain.

This changes only how the test observes the result; it does not increase the process-global CursorWindow size or weaken the migration scenario.

### Activity-hosted Compose test

Use the stable activity-hosted Compose JUnit rule supplied by `ui-test-junit4` and the existing debug test manifest dependency. Retain the current assertions that the trace card is displayed and that clicking Copy delivers the active trace ID.

## Test Strategy

Each repair follows a separate red-green cycle using the already captured failures as the red baseline:

1. Compile `highlight` instrumentation sources.
2. Run the app context test alone on the Galaxy.
3. Run the large migration test alone on the Galaxy.
4. Run the voice status-card test alone on the Galaxy.
5. Run repository-wide `connectedDebugAndroidTest` on the Galaxy.
6. Run the full non-device gate: unit tests, lint, and debug assembly.

Success requires zero instrumentation compilation errors and zero device-test failures. No test will be disabled, ignored, or conditioned on the Samsung model.

## Risks

- Compose BOM API differences may require the stable rule's exact import available in the resolved version. Compilation will verify this before device execution.
- The large migration may either migrate or skip the oversized conversation depending on device CursorWindow behavior. The assertions deliberately accept both valid outcomes while requiring preservation when skipped and clearing when migrated.
