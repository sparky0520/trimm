'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createGeminiDecisionProvider } = require('../server/gemini-agent.cjs');

test('generatePlan returns a sequence of tasks', async () => {
  let request;
  const provider = createGeminiDecisionProvider({ client: { models: { generateContent: async (value) => {
    request = value;
    return { text: JSON.stringify({
      tasks: [
        { id: 'task_1', description: 'Analyze audio levels', dependsOn: [] },
        { id: 'task_2', description: 'Remove silences', dependsOn: ['task_1'] }
      ]
    }), usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 10 } };
  } } } });

  const result = await provider.generatePlan({ prompt: 'Clean up audio and silences' });
  assert.equal(result.tasks.length, 2);
  assert.equal(result.tasks[1].dependsOn[0], 'task_1');
});
