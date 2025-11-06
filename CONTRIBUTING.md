# Contributing to Spring AI Replicate

Thanks for your interest in the project! I maintain this in my spare time, so the goal is to keep the contribution flow lightweight while still making sure we can ship stable, documented code.

## Before You Start
- Create a GitHub issue (or comment on an existing one) before writing code. This helps confirm the problem, align on scope, and avoid duplicate effort.
- Keep the scope small. Split larger ideas into multiple issues/PRs so they are quick to review.
- If you are unsure whether something fits the project, ask in the issue first.

## Development Workflow
- Fork the repo and create a feature branch from `main`.
- Build and test locally with.
- Add or update tests when you change behavior or add a feature. Failing tests should be fixed before submitting the PR.
- Update documentation (README, samples, Javadoc) when behavior, configuration, or dependencies change.

## Code Style and Quality
- Follow the existing formatting in the module you touch. If your IDE has auto-format rules set up, run them before committing.
- Prefer clear, minimal APIs over clever code. New dependencies should have a compelling reason—maintenance time is limited.
- Keep public APIs backwards compatible unless we have agreed on a breaking change in the related issue.
- Use descriptive commit messages; squash locally if a branch has noisy commits.

## Pull Requests
- Reference the related issue in the PR description and explain the intent of the change.
- Keep PRs focused. If you discover unrelated bugs or cleanup tasks, open separate issues.
- Make sure `mvn verify` passes and any new tests are included. If something cannot be tested, explain why in the PR.
- I review PRs as time allows. If you do not hear back within a week, feel free to add a gentle reminder.