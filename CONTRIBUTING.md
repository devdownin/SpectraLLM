# Contributing to Spectra — Domain LLM Builder

First off, thanks for taking the time to contribute! It's people like you that make Spectra such a great tool.

The following is a set of guidelines for contributing to Spectra.

## How Can I Contribute?

### Reporting Bugs
Bugs are tracked as GitHub issues. When creating a bug report, please include as many details as possible. Use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.md).

### Suggesting Enhancements
Enhancements are also tracked as GitHub issues. Please use the [Feature Request template](.github/ISSUE_TEMPLATE/feature_request.md).

### Pull Requests
- Follow the existing code style.
- Update the documentation for any changes.
- Add tests to cover your changes.
- Ensure all tests pass.
- Use the [Pull Request template](.github/PULL_REQUEST_TEMPLATE.md).

## Styleguides

### Documentation Guidelines
When contributing to the documentation in the `docs/` folder, please adhere to the following rules to maintain consistency with our MkDocs setup:
- **File Naming:** Always use lowercase `kebab-case` for markdown files (e.g., `getting-started.en.md`).
- **Language Suffixes:** Always append the language code to the file name (`.en.md` for English, `.fr.md` for French) to manage multilingual content.
- **Architecture Diagrams:** Use embedded [Mermaid.js](https://mermaid.js.org/) code blocks (` ```mermaid `) inside your markdown files instead of static images or raw HTML.
- **Local Testing:** You can preview your documentation changes locally by installing MkDocs (`pip install mkdocs-material mkdocs-mermaid2-plugin`) and running `mkdocs serve` in the repository root.

### Git Commit Messages
- Use the present tense ("Add feature" not "Added feature").
- Use the imperative mood ("Move cursor to..." not "Moves cursor to...").
- Limit the first line to 72 characters or less.
- Reference issues and pull requests liberally after the first line.

### Java Styleguide
- Follow standard Spring Boot coding conventions.
- Use meaningful variable and method names.
- Keep methods short and focused on a single task.

### React Styleguide
- Use functional components and hooks.
- Keep components small and reusable.
- Use Tailwind CSS for styling.

## Questions?
If you have any questions, please feel free to open an issue or contact the maintainers.
