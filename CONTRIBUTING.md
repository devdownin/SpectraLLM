# Contributing to Spectra — Domain LLM Builder

First off, thanks for taking the time to contribute! It's people like you that make Spectra such a great tool.

The following is a set of guidelines for contributing to Spectra.

## Local development

For the full install (models, Docker profiles, GPU), see **[Getting Started](docs/GETTING_STARTED.md)**. To build and test the code the way CI does, you need:

- **JDK 25** (Temurin recommended — `.sdkmanrc` pins `25-tem`, use `sdk env` if you have SDKMAN!)
- **Node.js 22** (frontend)
- **Python 3.11** (docparser / reranker services)

### Backend (Java / Spring Boot)

```bash
mvn -B package -f backend/pom.xml            # compile + run tests
mvn -Pstatic-analysis -DskipTests spotbugs:check -f backend/pom.xml   # SpotBugs + find-sec-bugs
```

### Frontend (React / Vite)

```bash
cd frontend
npm ci
npm run lint            # ESLint
npm run test:coverage   # Vitest
npm run build           # tsc + vite
```

### Python services

```bash
pip install -r services/requirements-test.txt
ruff check services/docparser services/reranker
(cd services/docparser && python -m pytest tests/ -q)
(cd services/reranker && python -m pytest tests/ -q)
```

Please run the relevant checks above before opening a pull request — they mirror what the CI enforces.

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
