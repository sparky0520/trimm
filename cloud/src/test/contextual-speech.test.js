import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { evaluateSpeechFixture, validateContextualSpeechDecision } from '../services/contextual-speech.js';

const fixturePath = path.join(__dirname, 'fixtures', 'contextual-speech-eval.v1.json');

test('versioned speech evaluation manifest covers every required delivery context', () => {
  const evaluation = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
  assert.equal(evaluation.schemaVersion, 1);
  assert.deepEqual(
    new Set(evaluation.cases.map((item) => item.scenario)),
    new Set(['slow', 'fast', 'non_native', 'emotional', 'comedic', 'instructional', 'multi_speaker', 'noisy_uncertain']),
  );
  assert.equal(evaluation.candidateModels[0].model, 'gemini-3.6-flash');
  assert.equal(evaluation.candidateModels.length, 1);
  assert.ok(evaluation.candidateModels.every((model) => model.status === 'pending_real_media_evaluation'));
  for (const item of evaluation.cases) {
    assert.deepEqual(evaluateSpeechFixture(item), {
      passed: true,
      forbiddenSoleBasis: false,
      contextualCut: true,
    }, item.id);
  }
});

test('fixed words, confidence, pauses, and quality cannot independently authorize a speech cut', () => {
  for (const basis of ['word_list', 'confidence_threshold', 'pause_threshold', 'quality_score']) {
    const result = evaluateSpeechFixture({ decision: 'cut', basis: [basis] });
    assert.equal(result.passed, false, basis);
    assert.equal(result.forbiddenSoleBasis, true, basis);
  }
});

test('speech operation authorization requires meaning, complete delivery, adaptive context, and uncertainty handling', () => {
  const evidence = new Set(['evidence-1']);
  const valid = {
    speechDecision: {
      meaningPreserved: true,
      completeDeliverySelected: true,
      rationale: 'The selected alternate says the complete thought and the simulated join is continuous.',
      contextEvidenceIds: ['evidence-1'],
      locatorSignals: ['possible_filler'],
      contextSignals: ['surrounding_context', 'speaker_scene_cadence', 'word_phoneme_timing', 'alternate_takes', 'user_intent', 'proposal_simulation'],
      transcriptUncertain: true,
      uncertaintyAction: 'alternate_transcription',
      technicalTieBreakerOnly: true,
    },
  };
  assert.equal(validateContextualSpeechDecision(valid, evidence).meaningPreserved, true);
  assert.throws(
    () => validateContextualSpeechDecision({
      speechDecision: { ...valid.speechDecision, uncertaintyAction: 'none' },
    }, evidence),
    (error) => error.code === 'contextual_speech_required',
  );
  assert.throws(
    () => validateContextualSpeechDecision({
      speechDecision: { ...valid.speechDecision, contextSignals: ['surrounding_context'] },
    }, evidence),
    (error) => error.code === 'contextual_speech_required',
  );
});
