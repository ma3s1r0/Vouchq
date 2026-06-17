# Vouchq verify — GitHub Action

A CI **build gate**: fail a pull request / build when a Skill in the repo is **not**
an `APPROVED` + pinned version in your Vouchq registry. This closes the governance
loop — you already distribute only vouched skills on the way *out*; this stops
unapproved or silently-changed ones from landing on the way *in*.

Vouchq stays a **registry**. The Action uploads the checked-out repo, Vouchq parses
each Skill and answers with a per-skill verdict — it never sits in your agents'
request path. If Vouchq is unreachable, only this check is affected (and you can run
it in `advisory` mode).

## How it decides

Identity is the **definition hash** (the same pin anchor Vouchq uses everywhere):
name + description + each file's `{path, sha256}`. Per skill:

| Verdict    | Meaning                                                              |
|------------|---------------------------------------------------------------------|
| `APPROVED` | Definition hash matches a pinned approved version — passes.          |
| `CHANGED`  | A skill of this name is approved, but the working tree diverges.     |
| `BLOCKED`  | A skill of this name is blocked.                                     |
| `UNKNOWN`  | Not registered in Vouchq.                                            |

The job passes only when **every** skill is `APPROVED`.

## Usage

```yaml
name: vouchq
on: [pull_request]
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: ma3s1r0/Vouchq/integrations/github-action@master
        with:
          vouchq-url: https://vouchq.example.com
          auth: ${{ secrets.VOUCHQ_AUTH }}   # a least-privilege VIEWER user, as EMAIL:PASSWORD
          # path: .            # directory to scan (default: repo root)
          # advisory: "true"   # warn instead of failing the job
```

`secrets.VOUCHQ_AUTH` should be the `EMAIL:PASSWORD` of a **VIEWER** user — the verify
endpoint (`POST /api/verify`) is read-only and accepts VIEWER+, so the CI token needs
no write access.
