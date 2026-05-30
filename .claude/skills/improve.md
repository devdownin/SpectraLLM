Review the selected code or file and suggest concrete improvements, then apply them.

Focus areas (in order of priority):
1. Correctness — fix bugs, edge cases, off-by-one errors, null/undefined risks
2. Clarity — rename unclear identifiers, remove misleading comments, simplify logic
3. Performance — remove redundant work, prefer efficient data structures
4. Safety — eliminate injection risks, validate at boundaries, handle errors properly

Rules:
- Only improve what is clearly wrong or unnecessarily complex; do not refactor for style alone
- Do not add features or handle hypothetical future cases
- Show a brief diff-style summary of what changed and why (one line per change)
- If args are provided, treat them as a focus hint (e.g. `/improve performance`)
