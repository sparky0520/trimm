'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('desktop planner defaults to Kimi and keeps OpenAI as an explicit option', () => {
  const server = fs.readFileSync(path.join(__dirname, '..', 'server', 'server.cjs'), 'utf8');
  assert.match(server, /CTRLB_DESKTOP_AI_PROVIDER \|\| 'kimi'/);
  assert.match(server, /provider === 'openai' \? process\.env\.OPENAI_API_KEY : process\.env\.MOONSHOT_API_KEY/);
  assert.match(server, /MOONSHOT_BASE_URL/);
  const core = fs.readFileSync(path.join(__dirname, '..', 'server', 'desktop-planner-core.cjs'), 'utf8');
  assert.match(core, /parsed\.reply = parsed\.message/);
  assert.match(core, /parsed\.mode = parsed\.type/);
});
