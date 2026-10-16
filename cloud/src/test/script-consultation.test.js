import test from 'node:test';
import assert from 'node:assert/strict';
import { getAgentTools } from '../schema/agent-decision-schema.js';
import { createOpenAIDecisionProvider } from '../services/openai-agent.js';

test('apply_schema_patch tool description instructs direct text for script analysis and improvements', () => {
  const tools = getAgentTools();
  const patchTool = tools.find(t => t.name === 'apply_schema_patch');
  assert.ok(patchTool, 'apply_schema_patch tool must exist');
  assert.match(
    patchTool.description,
    /DO NOT use this tool for conversational, editorial, script analysis, or script improvement requests/i,
    'Tool description must explicitly guide script requests to direct text'
  );
});

test('system policy includes reconstructed transcript, speech metrics, and script doctor instructions', async () => {
  let capturedPayload = null;

  const mockOpenAIClient = {
    chat: {
      completions: {
        create: async (payload) => {
          capturedPayload = payload;
          return (async function* () {
            yield { choices: [{ delta: { content: 'Script response' } }] };
          })();
        }
      }
    }
  };

  const provider = createOpenAIDecisionProvider({
    apiKey: 'mock-key',
    openaiClient: mockOpenAIClient
  });

  const mockTranscript = [
    { text: 'Hey', startMs: 100, endMs: 300, speakerId: 'spk1' },
    { text: 'guys', startMs: 320, endMs: 600, speakerId: 'spk1' },
    { text: 'so', startMs: 650, endMs: 800, speakerId: 'spk1' },
    { text: 'today', startMs: 2500, endMs: 2900, speakerId: 'spk1' }, // 1.7s silence
    { text: 'I', startMs: 3000, endMs: 3100, speakerId: 'spk1' },
    { text: 'want', startMs: 3150, endMs: 3300, speakerId: 'spk1' },
    { text: 'to', startMs: 3350, endMs: 3450, speakerId: 'spk1' },
    { text: 'explain', startMs: 3500, endMs: 3900, speakerId: 'spk1' },
    { text: 'trimm.', startMs: 3950, endMs: 4400, speakerId: 'spk1' }
  ];

  const sources = [{ uri: 'file://raw.mp4', durationMs: 5000 }];
  const schema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: true },
    timeline: [{ type: 'video', id: 'v1', src: 'file://raw.mp4', startMs: 0, endMs: 5000, sourceStartMs: 0 }]
  };

  await provider.chatCompletion({
    history: [{ role: 'user', content: 'analyse my video and suggest me improvements in the script' }],
    sources,
    transcript: mockTranscript,
    schema
  });

  assert.ok(capturedPayload, 'Payload must be passed to OpenAI client');
  const systemMessage = capturedPayload.messages.find(m => m.role === 'system');
  assert.ok(systemMessage, 'System message must be present');
  const content = systemMessage.content;

  // Verify rich reconstructed speech and script doctor instructions
  assert.match(content, /RECONSTRUCTED SPOKEN TEXT \(WHAT THE CREATOR SAID IN THIS ROUGH TAKE\):\s*"Hey guys so today I want to explain trimm\."/i);
  assert.match(content, /RECORDED VIDEO & SPEECH OVERVIEW:/i);
  assert.match(content, /SCRIPT DOCTOR & VIDEO ANALYSIS MASTER SPECIFICATION/i);
  assert.match(content, /CRITICAL ANTI-PATTERN: NEVER REGURGITATE THE RAW TRANSCRIPT/i);
  assert.match(content, /🎯 Video & Narrative Analysis/i);
  assert.match(content, /💡 Key Script Improvement Recommendations/i);
  assert.match(content, /📝 Structured & Polished Script \(Ready to Deliver\)/i);
  assert.match(content, /🔄 Before vs\. After \(Side-by-Side Polish\)/i);
  assert.match(content, /✂️ Next Steps in Trimm/i);
});

test('chatCompletion handles script consultation text response without slicing timeline video nodes', async () => {
  const scriptResponseText = `### 🎯 Video & Narrative Analysis
- **Core Intent & Message**: Introducing video editing with Trimm.
- **Hook & First 3 Seconds**: Started slowly with an awkward pause.

### 💡 Key Script Improvement Recommendations
- **Hook Strategy**: Open with: "Editing videos on mobile used to take hours."

### 📝 Structured & Polished Script (Ready to Deliver)
- **[01. Hook (0:00 - 0:03)]**: Stop editing videos the hard way.
- **[02. Core Value]**: Trimm lets you cut silences with one tap.
- **[03. Call to Action]**: Download the app today.

### 🔄 Before vs. After (Side-by-Side Polish)
- **Rough Take**: "Hey guys so today I want to explain trimm."
- **Polished Line**: "Here is the fastest way to edit mobile video."

### ✂️ Next Steps in Trimm
- I can cut the 1.7s silence at 0:01 in your video, or you can record take 2 with this script!`;

  const mockOpenAIClient = {
    chat: {
      completions: {
        create: async () => {
          return (async function* () {
            yield { choices: [{ delta: { content: scriptResponseText } }] };
          })();
        }
      }
    }
  };

  const provider = createOpenAIDecisionProvider({
    apiKey: 'mock-key',
    openaiClient: mockOpenAIClient
  });

  const mockTranscript = [
    { text: 'Hey', startMs: 100, endMs: 300, speakerId: 'spk1' },
    { text: 'guys', startMs: 320, endMs: 600, speakerId: 'spk1' },
    { text: 'so', startMs: 650, endMs: 800, speakerId: 'spk1' },
    { text: 'today', startMs: 2500, endMs: 2900, speakerId: 'spk1' }, // 1.7s silence
    { text: 'I', startMs: 3000, endMs: 3100, speakerId: 'spk1' },
    { text: 'want', startMs: 3150, endMs: 3300, speakerId: 'spk1' },
    { text: 'to', startMs: 3350, endMs: 3450, speakerId: 'spk1' },
    { text: 'explain', startMs: 3500, endMs: 3900, speakerId: 'spk1' },
    { text: 'trimm.', startMs: 3950, endMs: 4400, speakerId: 'spk1' }
  ];

  const sources = [{ uri: 'file://raw.mp4', durationMs: 5000 }];
  const schema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: true },
    timeline: [{ type: 'video', id: 'v1', src: 'file://raw.mp4', startMs: 0, endMs: 5000, sourceStartMs: 0 }]
  };

  const result = await provider.chatCompletion({
    history: [{ role: 'user', content: 'check the transcript and give me a structured script based on what I said in the video, and suggest some improvements' }],
    sources,
    transcript: mockTranscript,
    schema
  });

  // Verify result type is edit_program (consultation text response) and not schema_patch
  assert.equal(result.type, 'edit_program');
  assert.equal(result.summary, scriptResponseText);
  // Verify schema is undefined / not returned as a modified timeline
  assert.equal(result.schema, undefined);
});

test('chatCompletion fallback auto-slices video when user explicitly asks for timeline silence removal without tool call', async () => {
  const modelTextResponse = 'I have removed the awkward pauses and dead air from the video clips.';

  const mockOpenAIClient = {
    chat: {
      completions: {
        create: async () => {
          return (async function* () {
            yield { choices: [{ delta: { content: modelTextResponse } }] };
          })();
        }
      }
    }
  };

  const provider = createOpenAIDecisionProvider({
    apiKey: 'mock-key',
    openaiClient: mockOpenAIClient
  });

  const mockTranscript = [
    { text: 'Hey', startMs: 100, endMs: 500 },
    // 1500ms silence between 500ms and 2000ms
    { text: 'guys', startMs: 2000, endMs: 4000 }
  ];

  const sources = [{ uri: 'file://raw.mp4', durationMs: 5000 }];
  const schema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: true },
    timeline: [{ type: 'video', id: 'v1', src: 'file://raw.mp4', startMs: 0, endMs: 5000, sourceStartMs: 0 }]
  };

  const result = await provider.chatCompletion({
    history: [{ role: 'user', content: 'cut out the awkward pauses in the video' }],
    sources,
    transcript: mockTranscript,
    schema
  });

  // For explicit video cut requests, auto-slice kicks in when no tool is called
  assert.equal(result.type, 'schema_patch');
  assert.ok(result.schema, 'Schema must be returned with sliced video clips');
  const videoNodes = result.schema.timeline.filter(n => n.type === 'video');
  assert.ok(videoNodes.length > 1, 'Video must be sliced to omit the silence');
});

test('chatCompletion executes forced apply_schema_patch retry when model returns plain text claiming edits without tool call', async () => {
  let callCount = 0;
  let forcedPayloadCaptured = null;

  const mockOpenAIClient = {
    chat: {
      completions: {
        create: async (payload) => {
          callCount++;
          if (callCount === 1) {
            // First call: model hallucinates by returning plain text claiming edits
            return (async function* () {
              yield {
                choices: [{
                  delta: {
                    content: 'Fixed three things: (1) set ALL captions to posY 0.7, (2) corrected "Dream" to "Trimm" in caption.'
                  }
                }]
              };
            })();
          } else {
            // Second call: forced apply_schema_patch tool call
            forcedPayloadCaptured = payload;
            return (async function* () {
              yield {
                choices: [{
                  delta: {
                    tool_calls: [{
                      index: 0,
                      id: 'call_forced_1',
                      type: 'function',
                      function: {
                        name: 'apply_schema_patch',
                        arguments: JSON.stringify({
                          reason: 'Set posY to 0.7 and corrected caption to Trimm',
                          schema: {
                            timeline: [
                              {
                                type: 'text',
                                id: 'txt1',
                                text: 'Trimm in June',
                                style: { posY: 0.7 }
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
    }
  };

  const provider = createOpenAIDecisionProvider({
    apiKey: 'mock-key',
    openaiClient: mockOpenAIClient
  });

  const sources = [{ uri: 'file://raw.mp4', durationMs: 10000 }];
  const schema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: false },
    timeline: [
      { type: 'video', id: 'v1', src: 'file://raw.mp4', startMs: 0, endMs: 10000, sourceStartMs: 0 },
      { type: 'text', id: 'txt1', text: 'Dream in June', startMs: 1000, endMs: 3000, isVisible: true, style: { posY: -0.35, color: '#FFFFFF' } }
    ]
  };

  const result = await provider.chatCompletion({
    history: [{ role: 'user', content: 'Set caption posY to 0.7 and correct Dream to Trimm' }],
    sources,
    transcript: [{ text: 'Dream', startMs: 1000, endMs: 2000 }],
    schema
  });

  assert.equal(callCount, 2, 'Must have executed a forced tool call retry');
  assert.ok(forcedPayloadCaptured?.tool_choice, 'Must specify tool_choice for apply_schema_patch');
  assert.equal(forcedPayloadCaptured.tool_choice.function.name, 'apply_schema_patch');

  assert.equal(result.type, 'schema_patch');
  assert.ok(result.schema, 'Must return patched schema');
  const patchedText = result.schema.timeline.find(t => t.id === 'txt1');
  assert.ok(patchedText);
  assert.equal(patchedText.text, 'Trimm in June');
  assert.equal(patchedText.style.posY, 0.7);
  assert.equal(patchedText.style.color, '#FFFFFF', 'Should preserve unaffected style color');
  assert.match(result.summary, /Fixed three things/);
});

