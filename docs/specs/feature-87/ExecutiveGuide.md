# ExecutiveGuide Spec

**Feature:** #87 — Executive guide: page shell, navigation, and operator tools index
**Component:** `ExecutiveGuide` (`docs/executive-guide.html`)

## Overview

A static HTML page at `docs/executive-guide.html` written for an executive audience — no technical knowledge assumed. The page provides a plain-English orientation to the Movie Semantic Search project: what it does, how to bring it up, and where to find each operator tool. It uses the same dark GitHub theme as the existing docs site (`background: #0d1117`), includes a sticky sidebar table of contents, and is linked from `docs/index.html`.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| Page title | String | `Movie Semantic Search — Executive Guide` | Used in `<title>` and `<h1>` |
| Breadcrumb | String | `/ Architecture / Executive Guide` | Header breadcrumb; "Architecture" links to `index.html` |
| Sidebar TOC | Sticky HTML nav | Anchor links to each section | Fixed left rail; anchors: `#overview`, `#getting-started`, `#operator-tools`, `#walkthrough` |
| Section: System Overview | `<section id="overview">` | One non-technical paragraph describing what Movie Semantic Search does | No jargon; describe as a tool to search movies by meaning |
| Section: Getting Started | `<section id="getting-started">` | Numbered plain-English steps to start the system | Step 1: Start the services. Step 2: Load movie data. Step 3: Open the search interface. No CLI commands shown. |
| Section: Operator Tools | `<section id="operator-tools">` | HTML table listing all tools, their URLs, and one-sentence descriptions | See operator tools table below |
| Section: End-to-End Test Walkthrough | `<section id="walkthrough">` | Stub placeholder | Body: "Step-by-step testing instructions are coming soon." |

### Operator Tools Table

| Tool | URL | Plain-English Description |
|------|-----|--------------------------|
| Qdrant Dashboard | `http://localhost:6333/dashboard` | Inspect the movie database — browse what's stored and confirm the data loaded correctly |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` | Try the search API directly in the browser — type a query and see results without writing any code |
| Pipeline Runner | `make pipeline` (command-line) | Loads all movie data into the system — run this once after starting the services for the first time |
| Search Interface | `http://localhost:8080` | The movie search UI — the main product; type any description and find matching films |

All localhost URLs are for local development; production URLs depend on the deployment environment and should be substituted by the operator.

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `docs/index.html` | HTML file | Must be updated to add a `<a href="executive-guide.html">` navigation link |
| Swagger UI endpoint | URL | Provided by the SwaggerUiConfig task; URL is `http://localhost:8080/swagger-ui/index.html` |

### Dependency Mock Behaviors

Not applicable — this component is a static HTML file with no runtime dependencies.

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | Narrow viewport (<768px) | Sidebar TOC does not overlap main content | Use `position: sticky` with enough left margin on main | N/A |
| 2 | User opens page without services running | Operator tool links open to connection-refused | Expected behavior — links are localhost; no fix needed | N/A |
| 3 | Reader has no technical background | All sections readable without explanation | No CLI syntax, no code blocks, no acronyms without expansion | N/A |

## Unit Test Checklist

- [ ] `docs/executive-guide.html` exists
- [ ] `docs/index.html` contains `<a href="executive-guide.html">`
- [ ] Page `<title>` is `Movie Semantic Search — Executive Guide`
- [ ] All four section IDs exist: `overview`, `getting-started`, `operator-tools`, `walkthrough`
- [ ] Sidebar TOC contains four anchor links: `#overview`, `#getting-started`, `#operator-tools`, `#walkthrough`
- [ ] Operator tools table contains all four rows: Qdrant Dashboard, Swagger UI, Pipeline Runner, Search Interface
- [ ] Qdrant Dashboard link: `http://localhost:6333/dashboard`
- [ ] Swagger UI link: `http://localhost:8080/swagger-ui/index.html`
- [ ] Search Interface link: `http://localhost:8080`
- [ ] Body background is `#0d1117` (dark theme matches existing docs)
- [ ] No unexplained CLI commands, code snippets, or engineering jargon in visible page content
