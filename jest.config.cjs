/** First tests in this package — added with the FusekiStore error-handling fix. */
module.exports = {
  preset: 'ts-jest/presets/default-esm',
  testEnvironment: 'node',
  extensionsToTreatAsEsm: ['.ts'],
  moduleNameMapper: { '^(\\.{1,2}/.*)\\.js$': '$1' },
  transform: { '^.+\\.tsx?$': ['ts-jest', {
      useESM: true,
      // Transpile only. Under ts-jest, `@_linked/core` resolves to its published types rather
      // than the workspace source, so `@linkedShape` cannot see that FusekiStore extends Shape
      // and the decorator fails to typecheck — the same resolution friction CN's vitest config
      // documents at length. These tests assert RUNTIME behaviour; types are checked by the
      // package's own `yarn build`, which is the right place for them.
      diagnostics: false,
      tsconfig: { ...require('./tsconfig.json').compilerOptions, module: 'esnext', target: 'es2022', moduleResolution: 'node' },
    }] },
  testMatch: ['**/src/**/*.test.ts'],
};
