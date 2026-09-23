# Contributing to WatchMouse

Thanks for your interest in contributing to WatchMouse! Please keep the
following guidelines in mind.

## Reporting issues

- Use the GitHub issue tracker for this repository.
- Include the app version shown in the About screen, your watch model and
  Wear OS version, as well as a description of the problem and steps to
  reproduce it.

## Submitting changes

- Fork the repository and work on a feature branch.
- Keep changes focused on a single concern, with a descriptive commit message.
- Run `./gradlew :app:assembleDebug` before submitting; the pull request
  workflow also builds and lints the project automatically.

## Code style

- Follow the existing code conventions of the project.
- Do not introduce analytics, telemetry or any data collection.

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), consistent with the rest of this project.