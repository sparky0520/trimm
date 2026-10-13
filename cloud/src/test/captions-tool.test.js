import test from 'node:test';
import assert from 'node:assert/strict';
import { generateCaptionNodes } from '../services/openai-agent.js';
import { getAgentTools } from '../schema/agent-decision-schema.js';

test('agent tools include generate_captions and remove_captions', () => {
  const tools = getAgentTools();
  const toolNames = tools.map(t => t.name);
  assert.ok(toolNames.includes('generate_captions'), 'generate_captions tool should exist');
  assert.ok(toolNames.includes('remove_captions'), 'remove_captions tool should exist');
  assert.ok(toolNames.includes('apply_schema_patch'), 'apply_schema_patch tool should exist');
});

test('generateCaptionNodes returns empty array for empty inputs', () => {
  assert.deepEqual(generateCaptionNodes([], []), []);
  assert.deepEqual(generateCaptionNodes([{ startMs: 0, endMs: 5000, sourceStartMs: 0 }], []), []);
  assert.deepEqual(generateCaptionNodes([], [{ text: 'hello', startMs: 0, endMs: 500 }]), []);
});

test('generateCaptionNodes groups words into chunks and respects pauses', () => {
  const videoNodes = [
    { startMs: 0, endMs: 10000, sourceStartMs: 0 }
  ];
  const transcriptWords = [
    { text: 'Hello', startMs: 100, endMs: 300 },
    { text: 'world,', startMs: 350, endMs: 600 },
    { text: 'this', startMs: 650, endMs: 900 },
    { text: 'is', startMs: 950, endMs: 1200 },
    { text: 'Trimm.', startMs: 1250, endMs: 1600 },
    // 500ms pause
    { text: 'Welcome', startMs: 2200, endMs: 2500 },
    { text: 'aboard!', startMs: 2550, endMs: 2800 },
  ];

  const captions = generateCaptionNodes(videoNodes, transcriptWords, { color: '#FFFF00', fontSize: 80 }, 4);
  assert.equal(captions.length, 3);

  // Group 1: 4 words before 5th word
  assert.equal(captions[0].text, 'Hello world, this is');
  assert.equal(captions[0].startMs, 100);
  assert.equal(captions[0].endMs, 1200);
  assert.equal(captions[0].style.color, '#FFFF00');
  assert.equal(captions[0].style.fontSize, 80);

  // Group 2: 'Trimm.' before the pause
  assert.equal(captions[1].text, 'Trimm.');
  assert.equal(captions[1].startMs, 1250);
  assert.equal(captions[1].endMs, 1600);

  // Group 3: 'Welcome aboard!' after the pause
  assert.equal(captions[2].text, 'Welcome aboard!');
  assert.equal(captions[2].startMs, 2200);
  assert.equal(captions[2].endMs, 2800);
});

test('generateCaptionNodes maps timestamps correctly across cuts and ignores cut words', () => {
  // Video with a cut: 0-2000ms kept, 2000-5000ms cut, 5000-9000ms kept
  const videoNodes = [
    { startMs: 0, endMs: 2000, sourceStartMs: 0 },
    { startMs: 2000, endMs: 6000, sourceStartMs: 5000 }
  ];

  const transcriptWords = [
    { text: 'First', startMs: 500, endMs: 900 },
    { text: 'clip.', startMs: 950, endMs: 1400 },
    // This word is inside the cut region (source 2500-3500ms)
    { text: 'awkward silence words', startMs: 2500, endMs: 3500 },
    // This word is in the second clip (source 5200-5800ms)
    { text: 'Second', startMs: 5200, endMs: 5600 },
    { text: 'clip!', startMs: 5650, endMs: 6000 }
  ];

  const captions = generateCaptionNodes(videoNodes, transcriptWords);
  assert.equal(captions.length, 2);

  // First clip captions
  assert.equal(captions[0].text, 'First clip.');
  assert.equal(captions[0].startMs, 500);
  assert.equal(captions[0].endMs, 1400);

  // Second clip captions should be retimed to timeline (source 5200 - sourceStart 5000 = 200 offset -> startMs 2200)
  assert.equal(captions[1].text, 'Second clip!');
  assert.equal(captions[1].startMs, 2200);
  assert.equal(captions[1].endMs, 3000);
});
