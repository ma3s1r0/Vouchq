# Licensing

Vouchq is **fully open source under the GNU AGPL-3.0-or-later** — the entire
repository (control plane, console, and the `parser` / `scanner` libraries) is
covered by the root [`LICENSE`](LICENSE).

## Why AGPL-3.0 for everything

Vouchq is a *trust* product, and the whole stack — including the parser and
scanner — is vouchq-specific rather than general-purpose libraries. A single
strong copyleft keeps every part open even when run as a service:

- **Network clause (§13)** — running Vouchq (or any part of it) as a hosted
  service for others triggers the obligation to offer your modified source to
  those users. This prevents a closed-source fork, or a thin wrapper around the
  cores, being offered as a SaaS without contributing back.
- **One license, no boundaries** — no per-module split to reason about; the same
  terms apply everywhere.

## Using Vouchq

- **Self-hosting** for your own organization, modifying it, and redistributing it
  under the same terms is fully permitted — that is the intended use.
- The AGPL only requires you to offer your **modified** source to the users who
  interact with it over a network. Running an **unmodified** release for your own
  users imposes no source-distribution burden beyond the license notice.

## Contributing

Contributions are welcome across the whole stack. By submitting a contribution
you agree to license it under **AGPL-3.0-or-later** — the same terms as the rest
of the project.

## SPDX identifier

Whole repository: `AGPL-3.0-or-later`.
