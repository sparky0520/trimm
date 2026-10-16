import test from 'node:test';
import assert from 'node:assert/strict';
import { getAgentTools } from '../schema/agent-decision-schema.js';
import { createOpenAIDecisionProvider } from '../services/openai-agent.js';

test('apply_schema_patch and provide_script_advice tools exist and separate editing from script consultation', () => {
  const tools = getAgentTools();
  const patchTool = tools.find(t => t.name === 'apply_schema_patch');
  const adviceTool = tools.find(t => t.name === 'provide_script_advice');
  assert.ok(patchTool, 'apply_schema_patch tool must exist');
  assert.ok(adviceTool, 'provide_script_advice tool must exist');
  assert.match(
    patchTool.description,
    /For script analysis, suggestions, or critique without timeline changes, use provide_script_advice/i,
    'Tool description must explicitly guide script requests to provide_script_advice'
  );
  assert.match(
    adviceTool.description,
    /when NO modifications are being made to the video timeline or captions/i,
    'Advice tool description must forbid timeline modifications'
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

test('chatCompletion routes provide_script_advice tool call as script consultation without modifying schema', async () => {
  const scriptAdviceMarkdown = `### 🎯 Video & Narrative Analysis
- **Core Intent & Message**: Introducing video editing with Trimm.

### 💡 Key Script Improvement Recommendations
- **Hook Strategy**: Open with a relatable problem.

### 📝 Structured & Polished Script (Ready to Deliver)
- **[01. Hook]**: Editing mobile video just changed forever.`;

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
                    id: 'call_advice_1',
                    type: 'function',
                    function: {
                      name: 'provide_script_advice',
                      arguments: JSON.stringify({ adviceMarkdown: scriptAdviceMarkdown })
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

  const provider = createOpenAIDecisionProvider({
    apiKey: 'mock-key',
    openaiClient: mockOpenAIClient
  });

  const sources = [{ uri: 'file://raw.mp4', durationMs: 5000 }];
  const schema = {
    settings: { aspectRatio: '9:16', backgroundColor: '#000000', autoEnhance: true },
    timeline: [{ type: 'video', id: 'v1', src: 'file://raw.mp4', startMs: 0, endMs: 5000, sourceStartMs: 0 }]
  };

  const result = await provider.chatCompletion({
    history: [{ role: 'user', content: 'analyse my video and give me script improvements' }],
    sources,
    transcript: [{ text: 'Hey', startMs: 0, endMs: 500 }],
    schema
  });

  assert.equal(result.type, 'edit_program');
  assert.equal(result.summary, scriptAdviceMarkdown);
  assert.equal(result.schema, undefined);
});

test('chatCompletion executes tool invocation follow-up retry when model returns plain text without a tool call', async () => {
  let callCount = 0;
  let retryPayloadCaptured = null;

  const mockOpenAIClient = {
    chat: {
      completions: {
        create: async (payload) => {
          callCount++;
          if (callCount === 1) {
            // First call: model returns plain text claiming edits without tool call
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
            // Second call: retry triggers apply_schema_patch tool call
            retryPayloadCaptured = payload;
            return (async function* () {
              yield {
                choices: [{
                  delta: {
                    tool_calls: [{
                      index: 0,
                      id: 'call_retry_1',
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

  assert.equal(callCount, 2, 'Must have executed a tool invocation follow-up retry');
  assert.equal(retryPayloadCaptured?.tool_choice, undefined, 'Must not force invalid tool_choice that crashes models with thinking enabled');
  const retryLastMsg = retryPayloadCaptured.messages[retryPayloadCaptured.messages.length - 1];
  assert.match(retryLastMsg.content, /Textual unfulfilled claims of anything are forbidden/i);

  assert.equal(result.type, 'schema_patch');
  assert.ok(result.schema, 'Must return patched schema');
  const patchedText = result.schema.timeline.find(t => t.id === 'txt1');
  assert.ok(patchedText);
  assert.equal(patchedText.text, 'Trimm in June');
  assert.equal(patchedText.style.posY, 0.7);
  assert.equal(patchedText.style.color, '#FFFFFF', 'Should preserve unaffected style color');
  assert.match(result.summary, /Fixed three things/);
});

