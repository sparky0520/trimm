import OpenAI from 'openai';
import { getAgentTools } from '../schema/agent-decision-schema.js';
const nativeToolsSchema = [
  {
    type: 'function',
    function: {
      name: 'search_google_fonts',
      description: 'Search for a Google Font and get its direct .ttf URL.',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string', description: "The name of the font (e.g. 'Roboto', 'Open Sans')" }
        },
        required: ['query'],
        additionalProperties: false
      }
    }
  }
];

async function executeNativeTool(name, args) {
  if (name === 'search_google_fonts') {
    try {
      const { query } = JSON.parse(args);
      const FONTSOURCE_API = "https://api.fontsource.org/v1";
      const response = await fetch(`${FONTSOURCE_API}/fonts`);
      if (!response.ok) return "Failed to fetch fonts list";
      
      const fonts = await response.json();
      const match = fonts.find(f => f.family.toLowerCase().includes(query.toLowerCase()));
      
      if (!match) return `No font found matching "${query}"`;

      const detailResponse = await fetch(`${FONTSOURCE_API}/fonts/${match.id}`);
      const details = await detailResponse.json();
      
      // Attempt to extract the first available URL, prioritizing TTF
      const urls = Object.values(details.files || {});
      const ttfFile = urls.find(f => typeof f === 'string' && f.includes('.ttf')) || urls[0];
      return `Found font: ${match.family}. URL: ${ttfFile}`;
    } catch (e) {
      return `Error: ${e.message}`;
    }
  }
  return "Tool not found";
}

function createOpenAIDecisionProvider({
  apiKey = process.env.LLM_API_KEY,
  baseURL = process.env.LLM_OPENAI_BASE_URL || 'https://api.moonshot.ai/v1',
  model = process.env.MODEL || 'kimi-k3',
} = {}) {

  const openai = new OpenAI({
    apiKey,
    baseURL,
  });

  async function* chatCompletionStream({ history = [], projectSchema = {} }) {
    const systemPolicy = `You are an AI Video Editor Agent.
Your job is to execute the user's editing requests by proposing precise operations that mutate the current declarative Project Schema.
You have access to the chat history and the current Project Schema.

Current Project Schema:
${JSON.stringify(projectSchema, null, 2)}

You must ONLY respond by calling the provided tools. 
Do not explain your thought process in plain text. Simply execute the tool calls required to satisfy the user's request.`;

    const contents = history.map(msg => ({ role: msg.role, content: msg.content }));
    let currentMessages = [{ role: 'system', content: systemPolicy }, ...contents];
    
    const baseTools = getAgentTools();
    const tools = [
      ...baseTools.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } })),
      ...nativeToolsSchema
    ];

    console.log('[DEBUG] OpenAI Stream Request Initiated');

    while (true) {
      const requestPayload = {
        model,
        messages: currentMessages,
        temperature: 1,
        stream: true,
        tools
      };

      let stream;
      try {
        stream = await openai.chat.completions.create(requestPayload);
      } catch (err) {
        console.error(`[DEBUG] OpenAI API Error:`, err);
        throw err;
      }

      let toolCallId = null;
      let functionName = '';
      let functionArgs = '';

      let hasNativeCall = false;
      let nativeToolCallId = null;
      let nativeFunctionName = '';
      let nativeFunctionArgs = '';

      const handleCompletedToolCall = function* (id, name, args) {
        const isNative = nativeToolsSchema.some(t => t.function.name === name);
        if (isNative) {
          hasNativeCall = true;
          nativeToolCallId = id;
          nativeFunctionName = name;
          nativeFunctionArgs = args;
          currentMessages.push({ role: 'assistant', content: null, tool_calls: [{ id, type: 'function', function: { name, arguments: args } }] });
        } else {
          try {
            yield { type: 'tool_call', tool: name, args: JSON.parse(args) };
          } catch (e) {
            console.error("Failed to parse tool call args", args);
          }
        }
      };

      for await (const chunk of stream) {
        const delta = chunk.choices[0]?.delta;
        if (!delta) continue;

        if (delta.content) {
          yield { type: 'text', content: delta.content };
        }

        if (delta.tool_calls && delta.tool_calls.length > 0) {
          const tc = delta.tool_calls[0];
          
          if (tc.id) {
            if (toolCallId && functionName) {
              yield* handleCompletedToolCall(toolCallId, functionName, functionArgs);
            }
            toolCallId = tc.id;
            functionName = tc.function.name;
            functionArgs = tc.function.arguments || '';
          } else if (tc.function && tc.function.arguments) {
            functionArgs += tc.function.arguments;
          }
        }
      }

      if (toolCallId && functionName) {
        yield* handleCompletedToolCall(toolCallId, functionName, functionArgs);
      }

      if (hasNativeCall) {
        console.log(`[DEBUG] Executing Native Tool: ${nativeFunctionName}`);
        const result = await executeNativeTool(nativeFunctionName, nativeFunctionArgs);
        currentMessages.push({ role: 'tool', tool_call_id: nativeToolCallId, name: nativeFunctionName, content: result });
      } else {
        break; // Loop until no native tools are called
      }
    }
  }

  return { chatCompletionStream };
}

export { createOpenAIDecisionProvider };
