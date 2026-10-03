import js from '@eslint/js';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import architecture from './eslint/architecture.js';

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'playwright-report', 'test-results'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.strict, jsxA11y.flatConfigs.recommended],
    languageOptions: { ecmaVersion: 2024, globals: globals.browser },
    plugins: { 'react-hooks': reactHooks, architecture },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'architecture/feature-boundaries': 'error',
      'architecture/no-literal-permission': 'error',
      'architecture/no-literal-path': 'error',
      // Relative imports only: the boundary rule reads them.
      'no-restricted-imports': ['error', { patterns: [{ group: ['@/*', 'src/*'], message: 'Use a relative import.' }] }],
      eqeqeq: ['error', 'always'],
      'no-console': ['error', { allow: ['warn', 'error'] }],
    },
  },
  {
    // Tests may name permissions and paths literally: they state facts.
    files: ['**/*.test.{ts,tsx}', 'src/test/**', 'e2e/**'],
    rules: {
      'architecture/no-literal-permission': 'off',
      'architecture/no-literal-path': 'off',
    },
  },
  {
    files: ['eslint/**/*.js', '*.config.{js,ts}'],
    extends: [js.configs.recommended],
    languageOptions: { globals: globals.node },
  },
);
