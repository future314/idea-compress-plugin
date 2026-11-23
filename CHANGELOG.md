# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.3] - 2026-11-23

### Changed

- Plugin identity finalised ahead of the first Marketplace upload: id is now
  `lrong.file-compressor`, vendor is `lrong`. The id is permanent once uploaded
  and was checked against the Marketplace before being chosen.

### Added

- `signPlugin` and `publishPlugin` configuration. Signing certificate, private
  key and upload token are read from the `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
  `PRIVATE_KEY_PASSWORD` and `PUBLISH_TOKEN` environment variables and are
  never committed.

[0.1.3]: https://github.com/lrong/idea-compress-plugin/releases/tag/v0.1.3

## [0.1.2] - 2026-11-23

### Changed

- Selecting **several** items in the same directory now names the archive after
  that directory instead of the first selected item: pick `src`, `docs` and
  `README.md` inside `my-app` and get `my-app.zip`. Selections spanning
  different parents keep the previous behaviour and use the first item's name.
- The context-menu label always shows the name the archive will get, for both
  single and multiple selections.

[0.1.2]: https://github.com/lrong/idea-compress-plugin/releases/tag/v0.1.2

## [0.1.1] - 2026-11-23

### Fixed

- Exclusion patterns were applied to selected **file** roots, so right-clicking
  `report.zip` with the default `*.zip` pattern produced a silently empty
  archive. Roots are now exempt from exclusions, matching the documented
  behaviour and the existing handling for directory roots.
- A selection that could not be read (vanished, or ACL-denied) used to produce
  a valid-looking empty ZIP and a success balloon reading "0 files". It now
  fails with an error notification instead.
- A selected root that is itself a symlink was dropped, yielding an empty
  archive. The link target is now walked while keeping the link's own name.
- `resource-bundle` is also declared on `<actions>`, removing a
  "Cannot find plugin resource-bundle" warning from the IDE log.

[0.1.1]: https://github.com/lrong/idea-compress-plugin/releases/tag/v0.1.1

## [0.1.0] - 2026-11-23

### Added

- Right-click **Compress to ZIP** in the Project view and on editor tabs for
  one or more selected files and folders.
- The archive is written next to the selection and named after the first
  selected item (`src` -> `src.zip`), incrementing to `src (1).zip` on collision.
- Multiple selected items are packed into a single archive, each as a top-level
  entry; colliding names are disambiguated as `same.txt`, `same (1).txt`.
- Default exclusions for VCS metadata, IDE config, build output and dependency
  caches, editable in *Settings | Tools | File Compressor*.
- Background compression with progress and cancellation; the partial archive is
  removed if the operation fails or is cancelled.
- Completion notification with **Show in Explorer**.
- English and Simplified Chinese UI.

[Unreleased]: https://github.com/lrong/idea-compress-plugin/compare/v0.1.3...HEAD
[0.1.0]: https://github.com/lrong/idea-compress-plugin/releases/tag/v0.1.0
