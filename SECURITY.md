# Security Policy

<!-- The contact address below is a placeholder. Replace maintainer@example.com
     with the real maintainer contact address before publishing the repository. -->

## Supported versions

Security fixes are applied to the current minor line only.

| Version | Supported |
| --- | --- |
| 1.3.x | Yes (latest patch release) |
| 1.2.x and older | No |

Upgrade to the latest 1.3.x release before reporting an issue; reports against older versions may be closed as unsupported.

## Reporting a vulnerability

Please report suspected vulnerabilities privately:

1. Preferred: use GitHub private vulnerability reporting from the repository **Security** tab and choose **Report a vulnerability**. This creates a private channel with the maintainers.
2. If private reporting is unavailable, email maintainer@example.com with the subject `DAP security report`. Do not open a public issue, discussion, or pull request for a suspected vulnerability.

Include as much of the following as you can:

- Affected version or commit (`dap doctor` output or `VERSION`).
- Operating system, architecture, and JDK version.
- Step-by-step reproduction, including the smallest workflow, request, or input file.
- Actual and expected behavior, and the security impact you see.
- Relevant logs, with secrets and personal data removed.

Do not test against systems you do not own or have explicit permission to test. We will not pursue legal action against good-faith research that follows this policy and avoids privacy violations, service disruption, and data destruction.

## Scope and trust model

DAP is a single-user, local analytics engine: one JVM process, an HTTP `/v1` API, and a stdio MCP adapter. It has no authentication, authorization, or multi-tenancy. By default it binds only to 127.0.0.1, and the MCP adapter starts and talks to a local engine. Anyone who can run code as the local user can control the engine and read whatever that user can read; such local access is part of the trust model, not a vulnerability.

In scope:

- Breaking out of the loopback boundary or bypassing documented request limits without operator configuration.
- Arbitrary file read or write outside configured paths through node settings, workflows, recipes, or archive extraction.
- Remote code execution, command injection, or unsafe deserialization reachable through `/v1`, the MCP adapter, or the CLI.
- Installation weaknesses: non-HTTPS downloads, checksum bypass, use of `sudo`, or writes outside the documented install locations.
- Denial of service from a crafted workflow or input that defeats the documented limits.
- Leakage of local data through telemetry, recipes, bundled prompts, or error messages.

Out of scope:

- Vulnerabilities that require the attacker to already run arbitrary code as the local user.
- Exposing DAP deliberately to an untrusted network with `DAP_BIND` set to a non-loopback address. Do not do this: put DAP behind an OS firewall or an authenticated tunnel if remote access is required.
- Intentional SQL execution in `dap.sql_query` and user-authored file paths within documented settings.
- Issues in third-party dependencies alone: please report those upstream as well, and send us the upstream reference so we can plan an update.

Data stays on the local machine by design. `install.sh` downloads over HTTPS with a pinned SHA256, does not use `sudo`, and writes only under `~/.dap` and the user's shell configuration. Bundled recipes and prompts contain no secrets and make no network calls.

## Response timeline

These are targets, not guarantees; severe or complex reports may take longer.

- Acknowledgement within 3 business days.
- Initial triage and confirmation within 7 business days, with a request for more information if needed.
- Progress updates at least every 7 business days while the report is open.
- Target mitigation or fix: within 30 days for critical/high severity, within 90 days for moderate/low severity.
- Reports closed as out of scope get an explanation within 14 days.
- We follow coordinated disclosure: a fix and user notice are prepared before public details are shared. Please give us up to 90 days before publishing, and tell us your preferred credit in the advisory.
