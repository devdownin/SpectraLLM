Rewrite the selected code or text to be as concise as possible without losing correctness or readability.

Rules:
- Remove dead code, redundant variables, unnecessary intermediate assignments
- Collapse verbose conditionals and loops into idiomatic one-liners where the language supports it
- Eliminate boilerplate: prefer built-ins, standard-library helpers, and language idioms
- Do NOT sacrifice clarity for brevity — if the compact form is harder to read, keep the original
- Preserve all existing behaviour exactly; this is not a refactor, only a size reduction
- After rewriting, report the line-count delta (e.g. "52 → 34 lines, −35%")

If args are provided, treat them as a scope hint (e.g. `/compact imports` or `/compact this function`).
