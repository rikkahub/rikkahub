# Local Hermes PR Workflow

This checkout is the durable local workspace for the RikkaHub upstream PR that fixes model custom request configuration for background generation.

## Repository

- Path: `/home/muly/src/rikkahub`
- Upstream: `https://github.com/rikkahub/rikkahub`
- Branch: `fix/background-generation-custom-headers`
- Related issue: `https://github.com/rikkahub/rikkahub/issues/1281`

Do not use `/tmp/rikkahub-src` for development. That path is disposable and should be treated as read-only context/evidence.

## Local Setup

Initialize the repository and submodules:

```bash
git clone https://github.com/rikkahub/rikkahub /home/muly/src/rikkahub
cd /home/muly/src/rikkahub
git submodule update --init --recursive
```

Create `local.properties` with the Android SDK path:

```properties
sdk.dir=/home/muly/Android/Sdk
```

Install web UI dependencies before the first Android build:

```bash
cd /home/muly/src/rikkahub/web-ui
pnpm install --frozen-lockfile
```

For local debug builds, provide a debug-only Firebase placeholder at:

```text
app/src/debug/google-services.json
```

That file is local build setup. Do not include real Firebase credentials unless you intentionally need them for a separate local experiment.

## Build And Test

Run the targeted unit test:

```bash
cd /home/muly/src/rikkahub
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.service.ChatServiceTest'
```

Run all debug unit tests:

```bash
cd /home/muly/src/rikkahub
./gradlew :app:testDebugUnitTest
```

Build the debug APK:

```bash
cd /home/muly/src/rikkahub
./gradlew :app:assembleDebug
```

## Device

ADB-over-Tailscale docs live at:

```text
/home/muly/fressh/docs/adb-usb-over-tailscale.md
```

Current known device:

- ADB server: `tcp:100.69.79.32:5037`
- Serial: `R52Y903SQ7L`
- Model: `SM-X730`
- Android: `16`

Example install command:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb -s R52Y903SQ7L install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

## Hermes Verification

Use a custom OpenAI-compatible provider:

- base URL: `https://muly-hermes-api.core8.co/v1`
- model: `hermes-agent`
- provider API key: Hermes device API key
- model custom headers: Cloudflare Access client id and secret

Secrets are stored outside the repository. Do not commit API keys, device keys, Cloudflare Access tokens, raw headers, or unredacted logcat output.

Expected behavior after this patch:

- normal chat succeeds
- automatic title generation succeeds or at minimum no longer fails due to missing gateway headers
- background requests use the model custom headers/custom body

## Verification Result

Verified on `R52Y903SQ7L` with the debug package `me.rerere.rikkahub.debug`.

The verification used a local OpenAI-compatible mock server reachable from the tablet at `http://100.83.49.15:18081/v1`. The debug app was temporarily seeded with a fake provider/model whose model-level custom header was:

```text
X-RikkaHub-Verify: allow
```

The mock server returned `403` unless that header was present. After sending `Reply exactly: hermes-ok`, the server recorded:

```json
{"kind":"chat","path":"/v1/chat/completions","has_header":true,"stream":true}
{"kind":"title","path":"/v1/chat/completions","has_header":true,"stream":false}
```

The device UI showed both `hermes-ok` and `Local E2E Title`, confirming normal chat and automatic title generation completed with the model custom header applied.
