import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

/**
 * Flat ESLint config for the Spectra frontend.
 *
 * The rule set is intentionally lean and high-signal: it catches the class of
 * defects the codebase has actually hit (stale/incorrect effect dependencies,
 * hook-ordering violations, unused bindings) without drowning CI in stylistic
 * noise. Purely cosmetic rules are left off; genuine-bug rules are errors.
 */
export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'vite.config.*', 'eslint.config.js'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser, ...globals.es2022 },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      // Rules of Hooks and dependency correctness are the bug class this gate exists for.
      // Deliberately NOT spreading configs.recommended: since eslint-plugin-react-hooks v6
      // it enables the React Compiler rule set (purity, refs, immutability, …) as errors,
      // which is a much stricter gate than this lean config intends. In v5 the spread
      // added exactly the two rules below, so behaviour is unchanged.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // Unused code is a frequent source of stale logic; flag it, but let intentional
      // throwaways be prefixed with _.
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      // `any` is pervasive in the existing API/DTO layer. Fixing it properly is a
      // typed-DTO refactor of its own; surfacing it as a warning keeps the gate
      // green today while making the debt visible, rather than blocking on it.
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
);
