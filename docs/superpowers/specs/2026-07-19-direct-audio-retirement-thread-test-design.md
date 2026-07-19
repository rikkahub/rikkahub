# Direct Audio Retirement Thread Test Design

## Problem

`AndroidDirectAudioRouteControllerTest.retire does not wait for recorder configuration and late device lease retires locally`
waits for a `CountDownLatch` that the retirement worker counts down as its final statement, then immediately asserts that
the worker thread is no longer alive. Reaching the latch proves that `lease.retire()` returned, but it does not prove that
the JVM thread has completed termination. The resulting race makes the test reproducibly fail without indicating a
production defect.

## Scope

Change only the affected test. Do not modify production behavior, neighboring tests, or the existing defensive cleanup.

## Design

After `retirementCompleted.await(1, TimeUnit.SECONDS)` succeeds, call `retirement.join(1_000)` before checking
`retirement.isAlive`. The bounded join establishes the thread-termination condition that the assertion requires while
ensuring a regression fails instead of hanging the test indefinitely.

Keep the existing `finally` block unchanged. It must still release the blocked recorder configuration and join both
workers defensively when an earlier assertion fails.

## Verification

Run the affected class, then the full app JVM unit suite:

```shell
./gradlew :app:testDebugUnitTest --tests '*AndroidDirectAudioRouteControllerTest'
./gradlew :app:testDebugUnitTest
```

Success means both commands pass with no test failures.
