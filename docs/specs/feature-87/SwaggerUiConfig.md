# SwaggerUiConfig Spec

**Feature:** #87 — Executive guide: page shell, navigation, and operator tools index
**Component:** `SwaggerUiConfig` (`api/pom.xml`)

## Overview

Adds the `springdoc-openapi-starter-webmvc-ui` dependency to the Spring Boot API project. This auto-configures Swagger UI at `http://localhost:8080/swagger-ui/index.html` and an OpenAPI JSON endpoint at `http://localhost:8080/v3/api-docs` with zero additional Java configuration. The Swagger UI allows non-developers to browse and try the API endpoints visually in a browser, and is linked from the executive guide.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `artifactId` | Maven dependency | `springdoc-openapi-starter-webmvc-ui` | Added to `api/pom.xml` `<dependencies>` block |
| `version` | String | `2.3.0` | Compatible with Spring Boot 3.2.x; must use v2.x (not v1.x) |
| Swagger UI URL | String | `http://localhost:8080/swagger-ui/index.html` | Auto-configured by springdoc; no Java class needed |
| OpenAPI JSON URL | String | `http://localhost:8080/v3/api-docs` | Auto-generated from `@RestController` annotations |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `springdoc-openapi-starter-webmvc-ui` | Maven artifact | `pom.xml` `<dependency>` block under `org.springdoc` groupId |

### Dependency Mock Behaviors

Not applicable — this component is a Maven dependency addition; there is no runtime Java class with injected collaborators to unit-test.

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | springdoc v1.x used instead of v2.x | Startup failure or incorrect auto-configuration | Spring Boot 3.x requires springdoc v2.x | N/A — prevent by using version `2.3.0` exactly |
| 2 | Dependency added without `groupId` | Maven build failure | Must specify `org.springdoc` groupId | N/A — ensure full dependency block is correct |
| 3 | Existing tests with Spring context | All existing tests continue to pass | springdoc must not conflict with existing beans | N/A — verify with `./mvnw -f api/pom.xml test` |

## Unit Test Checklist

- [ ] `./mvnw -f api/pom.xml test` passes with the new dependency present
- [ ] No existing tests fail after the dependency addition
- [ ] `api/pom.xml` contains `springdoc-openapi-starter-webmvc-ui` version `2.3.0` under groupId `org.springdoc`
