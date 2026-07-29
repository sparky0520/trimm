'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.join(__dirname, '..', '..', '..');
const readJson = (relative) => JSON.parse(fs.readFileSync(path.join(root, relative), 'utf8'));

test('canonical agent schemas are versioned and compose one run decision', () => {
  const run = readJson('contracts/agent-run.v1.json');
  const activity = readJson('contracts/activity-event.v1.json');
  const evidence = readJson('contracts/evidence-request.v1.json');
  const editProgram = readJson('contracts/edit-program.v1.json');

  assert.equal(run.properties.contractVersion.const, 1);
  assert.equal(run.properties.events.items.$ref, 'activity-event.v1.json');
  assert.deepEqual(
    run.properties.decision.oneOf.slice(0, 2).map((entry) => entry.$ref),
    ['evidence-request.v1.json', 'edit-program.v1.json'],
  );
  assert.equal(activity.additionalProperties, false);
  assert.equal(evidence.properties.type.const, 'evidence_request');
  assert.equal(editProgram.properties.type.const, 'edit_program');
  assert.ok(editProgram.$defs.editOperation.properties.type.enum.includes('semantic_visual'));
});

test('Kotlin and TypeScript artifacts are deterministic and share the same fixture', () => {
  const result = spawnSync(process.execPath, [
    path.join(root, 'services', 'backend', 'scripts', 'generate-contract-models.cjs'),
    '--check',
  ], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);

  const canonical = fs.readFileSync(path.join(root, 'contracts', 'fixtures', 'agent-contracts.v1.json'), 'utf8');
  for (const language of ['kotlin', 'typescript']) {
    const fixture = fs.readFileSync(path.join(root, 'contracts', 'generated', language, 'agent-contracts.v1.json'), 'utf8');
    assert.equal(fixture, canonical);
  }
});

test('generated models expose the fixture discriminators and all canonical operation names', () => {
  const fixture = readJson('contracts/fixtures/agent-contracts.v1.json');
  const ts = fs.readFileSync(path.join(root, 'contracts', 'generated', 'typescript', 'contracts.v1.ts'), 'utf8');
  const runtime = fs.readFileSync(path.join(root, 'contracts', 'generated', 'typescript', 'contracts.v1.js'), 'utf8');
  const kt = fs.readFileSync(path.join(root, 'contracts', 'generated', 'kotlin', 'ContractsV1.kt'), 'utf8');

  assert.equal(fixture.evidenceRequest.type, 'evidence_request');
  assert.equal(fixture.editProgram.type, 'edit_program');
  assert.equal(fixture.agentRun.decision.type, 'edit_program');
  assert.match(ts, /type AgentDecisionV1/);
  assert.match(runtime, /SUPPORTED_CONTRACT_VERSIONS/);
  assert.match(kt, /sealed interface AgentDecisionV1/);
  assert.match(kt, /data class EditProgramV1[\s\S]*: AgentDecisionV1/);
  for (const operation of [
    'remove_fumbles', 'set_audio_cleanup', 'set_audio_levels', 'add_sound_effect',
    'set_background_music', 'add_captions', 'semantic_visual',
  ]) {
    assert.match(ts, new RegExp(operation));
    assert.match(kt, new RegExp(`SerialName\\("${operation}"\\)`));
  }
});
