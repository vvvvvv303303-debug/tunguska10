# Joint Test Host

`jointtesthost` is the neutral Android instrumentation host for cross-app Tunguska + Anubis validation.

Android instrumentation runs inside one package. Keeping the combined proof here prevents the production app test package from owning Anubis-specific orchestration.

## Responsibilities

- Launch and configure Anubis.
- Drive Tunguska through the exported automation contract.
- Launch `trafficprobe`.
- Prove direct-vs-tunneled public IP behavior.
- Verify Anubis freeze/unfreeze behavior around Tunguska.
- Validate both `XRAY_TUN2SOCKS` and `SINGBOX_EMBEDDED` through the runner.

## Canonical Proof

The canonical test is:

- `io.acionyx.tunguska.trafficprobe.AnubisJointUiProofTest`

Run it through:

- [tools/integration/run-anubis-e2e.ps1](../tools/integration/run-anubis-e2e.ps1)

Do not duplicate this flow inside `app/src/androidTest`.

## Diagnostics

The harness supports fast and full diagnostics through the runner's `-DiagnosticsMode` option.

- Fast: successful steps write lightweight markers; failures capture full evidence.
- Full: every successful step also captures screenshot and hierarchy.

The broader environment, privacy, and gating guidance is in [docs/e2e-testing.md](../docs/e2e-testing.md).
