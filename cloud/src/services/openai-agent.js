import OpenAI from 'openai';
import { getAgentTools } from '../schema/agent-decision-schema.js';

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
Do not explain your thought process in plain text. Simply execute the tool calls required to satisfy the user's request.
`;

    const contents = history.map(msg => ({ role: msg.role, content: msg.content }));
    const messages = [{ role: 'system', content: systemPolicy }, ...contents];
    const tools = getAgentTools();

    const requestPayload = {
      model,
      messages,
      temperature: 0.1,
      stream: true,
      tools: tools.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } }))
    };

    console.log('[DEBUG] OpenAI Stream Request Initiated');

    let stream;
    try {
      stream = await openai.chat.completions.create(requestPayload);
    } catch (err) {
      console.error(`[DEBUG] OpenAI API Error:`, err);
      throw err;
    }

    // We need to parse chunked tool calls. OpenAI streams them piecewise.
    // The `kimi-k3` API behaves identically to OpenAI.
    let toolCallId = null;
    let functionName = '';
    let functionArgs = '';

    for await (const chunk of stream) {
      const delta = chunk.choices[0]?.delta;
      if (!delta) continue;

      if (delta.content) {
        // Yield plain text if the model decides to speak
        yield { type: 'text', content: delta.content };
      }

      if (delta.tool_calls && delta.tool_calls.length > 0) {
        const tc = delta.tool_calls[0];
        
        // If it's a new tool call
        if (tc.id) {
          // If we were already tracking one, yield it before starting the new one
          if (toolCallId && functionName) {
            try {
              yield { type: 'tool_call', tool: functionName, args: JSON.parse(functionArgs) };
            } catch (e) {
              console.error("Failed to parse streamed function arguments", functionArgs);
            }
          }
          toolCallId = tc.id;
          functionName = tc.function.name;
          functionArgs = tc.function.arguments || '';
        } else if (tc.function && tc.function.arguments) {
          // Append to the current function arguments
          functionArgs += tc.function.arguments;
        }
      }
    }

    // Yield the very last tool call
    if (toolCallId && functionName) {
      try {
        yield { type: 'tool_call', tool: functionName, args: JSON.parse(functionArgs) };
      } catch (e) {
        console.error("Failed to parse final streamed function arguments", functionArgs);
      }
    }
  }

  return { chatCompletionStream };
}

export { createOpenAIDecisionProvider };
