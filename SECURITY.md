# Security Policy

## Supported versions

Only the latest release and the `main` branch receive security fixes.

## Reporting a vulnerability

Report vulnerabilities privately with GitHub private vulnerability reporting:
open the repository's **Security** tab and choose **Report a vulnerability**.
Do not open a public issue for a suspected vulnerability.

## Response targets

These targets are not binding. The project has a single maintainer and works on a best-effort basis.

- Acknowledge a report within 7 days.
- Give an initial assessment within 14 days.

## Scope and hardening

raftlab is an educational project and is not hardened for production use:

- The node and client ports have no TLS.
- The node and client ports have no authentication or authorization.
- Inbound frames are capped at 16 MiB (`FrameCodec.MAX_FRAME_BYTES`). A malformed or oversized inbound frame closes the connection.

Run raftlab only on trusted networks or on localhost.
