import { describe, expect, test } from '@jest/globals';
import { storedEndpoint } from './datasets.js';

describe('storedEndpoint', () => {
  test('appends the dataset and /stored', () => {
    expect(storedEndpoint('http://localhost:3030', 'cn-main')).toBe(
      'http://localhost:3030/cn-main/stored',
    );
  });

  test('trims trailing slashes of the base', () => {
    expect(storedEndpoint('http://fuseki:3030///', 'pg-test')).toBe(
      'http://fuseki:3030/pg-test/stored',
    );
  });
});
