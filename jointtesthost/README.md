# Joint Test Host

`jointtesthost` is the neutral Android instrumentation host for cross-app Tunguska + Anubis validation.

## Why It Exists

Android instrumentation has to live inside one package. Keeping the combined Tunguska + Anubis proof in this neutral host avoids coupling that cross-app flow to the production app test package.

This keeps the structure clear:

- Tunguska-only instrumentation stays in `app/src/androidTest`
- joint Tunguska + Anubis orchestration stays in `jointtesthost/src/androidTest`

## Canonical Joint Proof

The canonical proof is `io.acionyx.tunguska.trafficprobe.AnubisJointUiProofTest`.

Run it through [tools/integration/run-anubis-e2e.ps1](../tools/integration/run-anubis-e2e.ps1), not by duplicating the flow inside the production app module.

The full environment and privacy guidance are documented in [docs/e2e-testing.md](../docs/e2e-testing.md).