import test from 'node:test';
import assert from 'node:assert/strict';
import { getAgentTools, APPLY_SCHEMA_PATCH_TOOL } from '../schema/agent-decision-schema.js';

test('agent tools include apply_schema_patch with visible property for text nodes', () => {
  const tools = getAgentTools();
  const toolNames = tools.map(t => t.name);
  assert.ok(toolNames.includes('apply_schema_patch'), 'apply_schema_patch tool should exist');

  const patchTool = tools.find(t => t.name === 'apply_schema_patch');
  assert.ok(patchTool, 'apply_schema_patch tool must be present');
  
  const timelineItemProps = patchTool.parameters.properties.schema.properties.timeline.items.properties;
  assert.ok(timelineItemProps.visible, 'visible property must exist in timeline item schema');
  assert.equal(timelineItemProps.visible.type, 'boolean');
});

test('apply_schema_patch tool schema requires settings and timeline', () => {
  const schemaProps = APPLY_SCHEMA_PATCH_TOOL.parameters.properties.schema;
  assert.deepEqual(schemaProps.required, ['settings', 'timeline']);
  
  const settingsProps = schemaProps.properties.settings.properties;
  assert.ok(settingsProps.aspectRatio);
  assert.ok(settingsProps.backgroundColor);
  assert.ok(settingsProps.autoEnhance);
});

test('apply_schema_patch preserves video nodes and heals missing src when only text nodes are patched', async () => {
  const { createOpenAIDecisionProvider } = await import('../services/openai-agent.js');

  const mockOpenAIClient = {
    chat: {
      completions: {
        create: async () => {
          return (async function* () {
            yield {
              choices: [{
                delta: {
                  tool_calls: [{
                    index: 0,
                    id: 'call_1',
                    type: 'function',
                    function: {
                      name: 'apply_schema_patch',
                      arguments: JSON.stringify({
                        reason: 'Moved captions higher up',
                        schema: {
                          settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
                          timeline: [
                            {
                              type: 'text',
                              id: 'c1',
                              startMs: 0,
                              endMs: 2000,
                              text: 'Hello world',
                              visible: true,
                              style: { posY: 0.1, color: '#000000' }
                            }
                          ]
                        }
                      })
                    }
                  }]
                }
              }]
            };
          })();
        }
      }
    }
  };

  const provider = createOpenAIDecisionProvider({ apiKey: 'mock', openaiClient: mockOpenAIClient });
  const baseSchema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
    metadata: { transcripts: [], silences: [] },
    timeline: [
      { type: 'video', id: 'v1', src: 'content://media/123.mp4', startMs: 0, endMs: 5000, sourceStartMs: 0 },
      { type: 'text', id: 'c1', startMs: 0, endMs: 2000, text: 'Hello world', visible: false, style: { posY: -0.35, color: '#FFFFFF' } }
    ]
  };

  const res = await provider.chatCompletion({
    history: [{ role: 'user', content: 'Move captions higher up and make color black' }],
    sources: [{ uri: 'content://media/123.mp4', durationMs: 5000 }],
    transcript: [],
    schema: baseSchema
  });

  assert.equal(res.type, 'schema_patch');
  assert.ok(res.schema);
  const videoNode = res.schema.timeline.find(n => n.type === 'video');
  assert.ok(videoNode, 'Existing video node must be preserved even if omitted by LLM');
  assert.equal(videoNode.src, 'content://media/123.mp4', 'Video src must be preserved');
  const textNode = res.schema.timeline.find(n => n.type === 'text');
  assert.equal(textNode.style.posY, 0.1);
  assert.equal(textNode.style.color, '#000000');
  assert.equal(res.schema.metadata, baseSchema.metadata, 'Metadata must be preserved');
});

test('apply_schema_patch auto-heals video node if model emits video node with missing src', async () => {
  const { createOpenAIDecisionProvider } = await import('../services/openai-agent.js');

  const mockOpenAIClient = {
    chat: {
      completions: {
        create: async () => {
          return (async function* () {
            yield {
              choices: [{
                delta: {
                  tool_calls: [{
                    index: 0,
                    id: 'call_2',
                    type: 'function',
                    function: {
                      name: 'apply_schema_patch',
                      arguments: JSON.stringify({
                        reason: 'Updated caption',
                        schema: {
                          settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
                          timeline: [
                            // Model emitted video node but dropped src!
                            { type: 'video', id: 'v1', startMs: 0, endMs: 5000 },
                            { type: 'text', id: 'c1', startMs: 0, endMs: 2000, text: 'Hi', style: { posY: 0.2 } }
                          ]
                        }
                      })
                    }
                  }]
                }
              }]
            };
          })();
        }
      }
    }
  };

  const provider = createOpenAIDecisionProvider({ apiKey: 'mock', openaiClient: mockOpenAIClient });
  const baseSchema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
    timeline: [
      { type: 'video', id: 'v1', src: 'content://media/123.mp4', startMs: 0, endMs: 5000, sourceStartMs: 0 }
    ]
  };

  const res = await provider.chatCompletion({
    history: [{ role: 'user', content: 'Move captions up' }],
    sources: [{ uri: 'content://media/123.mp4', durationMs: 5000 }],
    transcript: [],
    schema: baseSchema
  });

  const videoNode = res.schema.timeline.find(n => n.type === 'video');
  assert.ok(videoNode);
  assert.equal(videoNode.src, 'content://media/123.mp4', 'Missing src must be backfilled from previous schema');
});

test('mergeSchemaPatch: changes property value, removes property with null/0, adds property, and preserves unaffected attributes', async () => {
  const { mergeSchemaPatch } = await import('../services/openai-agent.js');
  const baseSchema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
    timeline: [
      {
        type: 'text',
        id: 'c1',
        text: 'Hello world',
        startMs: 0,
        endMs: 2000,
        style: {
          color: '#FFFFFF',
          posY: -0.35,
          fontSize: 55,
          strokeColor: '#000000',
          strokeWidth: 3
        }
      }
    ]
  };

  const patchSchema = {
    timeline: [
      {
        type: 'text',
        id: 'c1',
        style: {
          color: '#FFFF00',
          strokeWidth: 0,
          strokeColor: null,
          scale: 1.5
        }
      }
    ]
  };

  const merged = mergeSchemaPatch(baseSchema, patchSchema);
  const textNode = merged.timeline.find(n => n.id === 'c1');
  assert.ok(textNode);
  assert.equal(textNode.text, 'Hello world', 'Unchanged node properties must be preserved');
  assert.equal(textNode.style.color, '#FFFF00', 'Changed property value must be updated');
  assert.equal(textNode.style.scale, 1.5, 'Newly added property must be present');
  assert.equal(textNode.style.posY, -0.35, 'Omitted style property must be preserved');
  assert.equal(textNode.style.fontSize, 55, 'Omitted style property must be preserved');
  assert.equal(textNode.style.strokeColor, undefined, 'Nullified strokeColor must be removed');
  assert.equal(textNode.style.strokeWidth, undefined, 'Zeroed strokeWidth must be removed');
});

test('mergeSchemaPatch: removes nodes via removedNodeIds and deleted: true', async () => {
  const { mergeSchemaPatch } = await import('../services/openai-agent.js');
  const baseSchema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
    timeline: [
      { type: 'video', id: 'v1', src: 'content://media/1.mp4', startMs: 0, endMs: 5000 },
      { type: 'audio', id: 'bgm-1', src: 'content://media/bgm.mp3', startMs: 0, endMs: 5000 },
      { type: 'effect', id: 'fx-1', effectName: 'vintage', startMs: 0, endMs: 5000 }
    ]
  };

  const patchSchema = {
    timeline: [
      { id: 'fx-1', deleted: true }
    ]
  };

  const merged = mergeSchemaPatch(baseSchema, patchSchema, [], ['bgm-1']);
  assert.equal(merged.timeline.length, 1);
  assert.equal(merged.timeline[0].id, 'v1');
  assert.equal(merged.timeline.find(n => n.id === 'bgm-1'), undefined);
  assert.equal(merged.timeline.find(n => n.id === 'fx-1'), undefined);
});

test('mergeSchemaPatch: heals multiple video nodes without src by mapping to sources inventory', async () => {
  const { mergeSchemaPatch } = await import('../services/openai-agent.js');
  const baseSchema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
    timeline: [
      { type: 'video', id: 'clip-0', src: 'content://media/clip1.mp4', startMs: 0, endMs: 5000 }
    ]
  };

  const sources = [
    { id: 'content://media/clip1.mp4', durationMs: 5000 },
    { id: 'content://media/clip2.mp4', durationMs: 5000 },
    { id: 'content://media/clip3.mp4', durationMs: 5000 }
  ];

  // Model emits montage of 3 clips with no src on newly added clips
  const patchSchema = {
    timeline: [
      { type: 'video', id: 'v1', startMs: 0, endMs: 4000 },
      { type: 'video', id: 'v2', startMs: 4000, endMs: 8000 },
      { type: 'video', id: 'v3', startMs: 8000, endMs: 12000 }
    ]
  };

  const merged = mergeSchemaPatch(baseSchema, patchSchema, sources);
  assert.equal(merged.timeline.length, 3);
  assert.equal(merged.timeline[0].src, 'content://media/clip1.mp4');
  assert.equal(merged.timeline[1].src, 'content://media/clip2.mp4');
  assert.equal(merged.timeline[2].src, 'content://media/clip3.mp4');
});



