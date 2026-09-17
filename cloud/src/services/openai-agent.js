import OpenAI from 'openai';
import { getAgentTools } from '../schema/agent-decision-schema.js';
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let mcpClientInstance = null;
let mcpToolsCache = null;

async function getMcpClient() {
  if (mcpClientInstance) return mcpClientInstance;
  try {
    const transport = new StdioClientTransport({
      command: "node",
      args: [path.join(__dirname, "../../../../.agents/mcp/google-fonts/src/index.js")]
    });
    const client = new Client({ name: "trimm-backend", version: "1.0.0" }, { capabilities: { tools: {} } });
    await client.connect(transport);
    mcpClientInstance = client;
    return client;
  } catch (e) {
    console.error("Failed to initialize MCP client", e);
    return null;
  }
}

async function getMcpTools() {
  if (mcpToolsCache) return mcpToolsCache;
  const client = await getMcpClient();
  if (!client) return [];
  try {
    const listRes = await client.listTools();
    mcpToolsCache = listRes.tools.map(t => ({
      type: 'function',
      function: {
        name: t.name,
        description: t.description,
        parameters: t.inputSchema
      }
    }));
    return mcpToolsCache;
  } catch (e) {
    console.error("Failed to list MCP tools", e);
    return [];
  }
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
    const mcpTools = await getMcpTools();
    const tools = [
      ...baseTools.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } })),
      ...mcpTools
    ];

    console.log('[DEBUG] OpenAI Stream Request Initiated');

    while (true) {
      const requestPayload = {
        model,
        messages: currentMessages,
        temperature: 0.1,
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

      let hasMcpCall = false;
      let mcpToolCallId = null;
      let mcpFunctionName = '';
      let mcpFunctionArgs = '';

      const handleCompletedToolCall = function* (id, name, args) {
        const isMcp = mcpTools.some(t => t.function.name === name);
        if (isMcp) {
          hasMcpCall = true;
          mcpToolCallId = id;
          mcpFunctionName = name;
          mcpFunctionArgs = args;
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

      if (hasMcpCall) {
        console.log(`[DEBUG] Executing MCP Tool: ${mcpFunctionName}`);
        const client = await getMcpClient();
        try {
          const parsedArgs = JSON.parse(mcpFunctionArgs);
          const result = await client.callTool({ name: mcpFunctionName, arguments: parsedArgs });
          const textResult = result.content[0].text;
          currentMessages.push({ role: 'tool', tool_call_id: mcpToolCallId, name: mcpFunctionName, content: textResult });
        } catch (e) {
          console.error(`[DEBUG] MCP Tool Error:`, e);
          currentMessages.push({ role: 'tool', tool_call_id: mcpToolCallId, name: mcpFunctionName, content: `Error: ${e.message}` });
        }
      } else {
        break; // Loop until no MCP tools are called
      }
    }
  }

  return { chatCompletionStream };
}

export { createOpenAIDecisionProvider };
