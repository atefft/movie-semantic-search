# ExecutiveGuideHtml Spec

**Feature:** #205 — Update docs/executive-guide.html to reference start-services.sh and stop-services.sh
**Component:** `ExecutiveGuideHtml` (`docs/executive-guide.html`)

## Overview

`docs/executive-guide.html` is the primary human- and agent-facing guide for setting up and operating Movie Semantic Search. The current "Getting Started" section and the "Prerequisites" sub-section of the walkthrough both instruct users to run three separate `docker compose` commands (`load-model`, `up -d`, `load-data`). With the addition of `start-services.sh` and `stop-services.sh` (features #203 and #204), these manual steps must be replaced by references to the new entry-point scripts. The goal is to reduce confusion for both human users and agents (e.g., the `/uat` tool) by providing a single, predictable startup command.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `start-services.sh --rebuild` | shell script invocation | Full teardown and rebuild from scratch | Must be run from repository root; exports model, rebuilds images, loads data |
| `start-services.sh` (no flags) | shell script invocation | Fast startup using existing images and volumes | Skips model export and data load; suitable for everyday restarts |
| `stop-services.sh` | shell script invocation | Spins down all containers, preserves volumes | Fast path — next `start-services.sh` (no flags) will reuse volumes |
| `stop-services.sh --clean` | shell script invocation | Full teardown including volumes | Use when a completely clean state is required before `--rebuild` |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| None | — | This is a static HTML documentation file with no runtime dependencies |

### Dependency Mock Behaviors

No runtime dependencies. This component is a static HTML file.

## Sections to Change

### 1. "Getting Started" section (`id="getting-started"`)

**Current content:** 4 numbered `<li>` items — export model, start services, load data, open search interface.

**Required content:**
- `<li>` 1: Run `./start-services.sh --rebuild` for an initial setup or any time a fresh start is needed (rebuilds images and loads all data). Include a note that the `/uat` tool should always use `--rebuild` to avoid stale containers.
- `<li>` 2 (informational inline note, not a separate step): For everyday startup when services were previously set up, run `./start-services.sh` (no flags) — uses existing images and volumes without reloading data.
- `<li>` 3 (formerly step 4): Open the Search Interface (unchanged).

**Preservation requirement:** All other content — TMDB API key setup, Docker prerequisites, access URLs — must remain unchanged.

### 2. "Prerequisites" sub-section inside the walkthrough (`id="walkthrough"`)

**Current content:** 7 numbered `<li>` items — steps 1–3 are TMDB/env/Docker setup; steps 4–6 are the three docker-compose commands; step 7 is "Confirm the system is ready."

**Required content:** Replace steps 4, 5, and 6 with a single step: run `./start-services.sh --rebuild` from the repository root (same description as Getting Started step 1). Steps 1–3 and step 7 remain unchanged; step 7 renumbers to step 5.

### 3. New "Stopping Services" sub-section

**Location:** Add a new `<section id="stopping-services">` between the "Getting Started" and "Operator Tools" sections. Also add a corresponding `<a href="#stopping-services">Stopping Services</a>` link to the `<nav class="toc">`.

**Required content:**
- `./stop-services.sh` — Stops all containers and preserves volumes for fast restart with `./start-services.sh`.
- `./stop-services.sh --clean` — Full teardown including volumes; use before `./start-services.sh --rebuild` when a completely clean state is required.

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | Agent following the guide with `/uat` | Agent runs `./start-services.sh --rebuild` | The note must be prominent enough that an agent reading the guide chooses `--rebuild` | N/A — static HTML |
| 2 | User reading guide for everyday startup | User understands `./start-services.sh` (no flags) is the fast path | The distinction between `--rebuild` and no-flag must be clear from the one-line descriptions | N/A — static HTML |
| 3 | User wants to wipe and restart | User runs `./stop-services.sh --clean` then `./start-services.sh --rebuild` | The "Stopping Services" section must make the `--clean` + `--rebuild` pairing explicit | N/A — static HTML |

## Unit Test Checklist

This is a documentation-only change; there are no unit tests. Acceptance is verified by manual inspection of the rendered HTML:

- [ ] "Getting Started" section contains no references to `docker compose run --rm load-model`, `docker compose up -d`, or `docker compose run --rm load-data`
- [ ] "Getting Started" step 1 references `./start-services.sh --rebuild` with a one-line description
- [ ] "Getting Started" section includes a note that `/uat` should use `--rebuild`
- [ ] "Getting Started" section mentions `./start-services.sh` (no flags) for everyday startup
- [ ] The "Prerequisites" sub-section of the walkthrough contains no references to the three old docker-compose commands
- [ ] The walkthrough "Prerequisites" has a single step replacing the old steps 4–6, referencing `./start-services.sh --rebuild`
- [ ] A "Stopping Services" sub-section exists between "Getting Started" and "Operator Tools"
- [ ] "Stopping Services" describes both `./stop-services.sh` and `./stop-services.sh --clean` with one-line descriptions
- [ ] TOC nav includes a link to `#stopping-services`
- [ ] All other content (TMDB key setup, Docker install, access URLs, walkthrough steps 1–3 and confirmation step) is unchanged
