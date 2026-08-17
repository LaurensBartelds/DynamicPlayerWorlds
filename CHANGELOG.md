# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The Minecraft version an artifact is built against is build metadata, carried in
the jar filename (`+mc<version>`), not part of the project version.

## [Unreleased]

### Added

- Specification v0.4, with Minecraft version gating (section 12.9), the control
  plane (section 13), self-fencing at lease expiry (MN-10b) and the quiesce,
  snapshot, verify procedure (MN-5a, MN-5c).
- Foundation plan `docs/plans/00-repo-foundation.md` covering tasks F0–F12.
- Repository foundation: build, quality gates, module layout, decision records.
