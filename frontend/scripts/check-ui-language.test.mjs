import assert from 'node:assert/strict';
import test from 'node:test';
import { inspectSource, rulesForText } from './check-ui-language.mjs';

test('text can report glossary, status and ASCII Turkish rules together', () => {
  assert.deepEqual(rulesForText('workspace Done gorev'), ['GLOSSARY', 'STATUS_NAME', 'ASCII_TURKISH']);
});

test('exact protocol status names remain exempt from the status rule', () => {
  for (const status of ['Done', 'Review', 'In Progress', 'To Do']) {
    assert.deepEqual(rulesForText(status), []);
  }
  assert.deepEqual(rulesForText('Status: Done'), ['STATUS_NAME']);
});

test('point suffix after an expression uses the glossary rule', () => {
  assert.deepEqual(rulesForText('sp', false), []);
  assert.deepEqual(rulesForText('sp', true), ['GLOSSARY']);
});

test('source inspection preserves locations and applies the allowlist', () => {
  const source = '<button title="gorev">workspace</button>';
  const violations = inspectSource('Example.tsx', source);
  assert.deepEqual(violations.map(({ rule }) => rule), ['ASCII_TURKISH', 'GLOSSARY']);
  assert.deepEqual(violations.map(({ line, column }) => [line, column]), [[1, 16], [1, 23]]);
  assert.deepEqual(inspectSource('Example.tsx', source, new Set(['gorev', 'workspace'])), []);
});
