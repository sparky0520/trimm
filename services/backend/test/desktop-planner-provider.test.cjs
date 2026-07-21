'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('desktop planner defaults to Kimi and keeps OpenAI as an explicit option', () => {
  const route = fs.readFileSync(path.join(__dirname, '..', 'api', 'desktop-plan.js'), 'utf8');
  assert.match(route, /CTRLB_DESKTOP_AI_PROVIDER \|\| 'kimi'/);
  assert.match(route, /provider === 'openai' \? process\.env\.OPENAI_API_KEY : process\.env\.MOONSHOT_API_KEY/);
  assert.match(route, /MOONSHOT_BASE_URL/);
  const core = fs.readFileSync(path.join(__dirname, '..', 'server', 'desktop-planner-core.cjs'), 'utf8');
  assert.match(core, /parsed\.reply = parsed\.message/);
  assert.match(core, /parsed\.mode = parsed\.type/);
});
