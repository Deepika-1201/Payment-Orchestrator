# ADR-033: Dependency and image vulnerability gates

**Status:** Accepted (2026-10-05)

## Context
CI already scans the Terraform with Trivy (ADR-028), but nothing scanned what actually ships: the application jar and its container image. A Trivy scan of the jar on 2026-10-05 found 10 published vulnerabilities, all in versions that Spring Boot 4.1.1's dependency management (its BOM) pins:

- Tomcat 11.0.24: three critical (a security-constraint bypass, a DIGEST authentication replay, and a FORM authentication bypass). Fixed in 11.0.25.
- Jackson 3.1.5: five high and two medium. Most are denial of service through crafted JSON (regex backtracking, unbounded buffers, numeric parsing, cache growth); the others are path handling in `Path` deserialization. Fixed in 3.1.7.

The gateway parses untrusted JSON on every public endpoint, so the Jackson issues matter even though the gateway does not use the affected Tomcat authenticators. Spring Boot 4.1.1 was still the latest 4.1 release, so upgrading Boot was not an option.

## Decision
- **Patch ahead of the BOM.** `build.gradle.kts` overrides Boot's managed `tomcat.version` (11.0.26) and `jackson-bom.version` (3.1.7). Both are patch releases on the same minor line. Remove the overrides once a Boot release manages these versions or newer ones.
- **Gate the jar.** After the build and tests, the build job runs `trivy rootfs` on `build/libs` and fails on any HIGH or CRITICAL vulnerability that has a fix.
- **Gate the image.** The container job tags the image that compose built and runs `trivy image` on it with the same rule. This covers the base image's OS packages (`eclipse-temurin:25-jre`, Ubuntu 26.04) as well as the jar.
- **Pinned scanner.** Trivy is pinned (0.74.0) like every other CI tool. Its vulnerability database updates daily, so a build can turn red without any code change when a new fix is published.

## Alternatives

| Option | Trade-off |
|---|---|
| Wait for the next Spring Boot release | Leaves three critical and five high vulnerabilities in the shipped artifact for an unknown time |
| Fail on MEDIUM as well | Noisier: medium findings without practical exposure would block merges. The Terraform scan already uses MEDIUM because misconfigurations are cheap to fix |
| Fail on unfixed vulnerabilities too | A build nobody can fix is a build people learn to ignore. Unfixed findings stay visible in a local scan without `--ignore-unfixed` |
| OWASP Dependency-Check | Slower (NVD download), Java only, and it doesn't cover the OS packages. Trivy is already in the toolchain |
| Scan only the image | The jar scan fails in the build job, before the container job's slower steps, and points straight at the library |
| Renovate or Dependabot pull requests | Complements the gates by proposing upgrades, but opens automated PRs on the public repository. Left to the maintainer to enable |

## Consequences
- The shipped jar has no known vulnerabilities at any severity (Trivy, 100 libraries), and the runtime base image has no fixable HIGH or CRITICAL ones.
- The full test suite passes on Tomcat 11.0.26 and Jackson 3.1.7.
- A newly published fix for a HIGH or CRITICAL vulnerability turns CI red until the dependency or the base image is bumped. That is intended.
- To check locally: `./gradlew bootJar` and `trivy rootfs --scanners vuln build/libs`.
