import plannerCore from '../server/planner-core.cjs';
import desktopPlanner from '../server/desktop-planner-core.cjs';

export default async function desktopPlan(request, response) {
  if (request.method !== 'POST') return response.status(405).json({ error: 'Method not allowed' });
  if (!plannerCore.authorized(request.headers.authorization)) return response.status(401).json({ error: 'Unauthorized' });
  const payload = request.body;
  const frames = Array.isArray(payload?.frameImages) ? payload.frameImages : [];
  if (!payload || !desktopPlanner.validateRequest(payload.request) || frames.length > 6 || frames.some((frame) =>
    !frame || typeof frame.id !== 'string' || typeof frame.data !== 'string' || frame.data.length > 1_000_000 || !/^[a-zA-Z0-9+/=]+$/.test(frame.data))) {
    return response.status(400).json({ error: 'Invalid desktop planner request' });
  }
  const frameDataById = Object.fromEntries(frames.map((frame) => [frame.id, frame.data]));
  const provider = String(process.env.CTRLB_DESKTOP_AI_PROVIDER || 'kimi').toLowerCase();
  if (!['kimi', 'openai'].includes(provider)) return response.status(503).json({ error: 'Unsupported desktop AI provider configuration' });
  const result = await desktopPlanner.createAiPlanner({
    provider,
    apiKey: provider === 'openai' ? process.env.OPENAI_API_KEY : process.env.MOONSHOT_API_KEY,
    baseURL: provider === 'kimi' ? process.env.MOONSHOT_BASE_URL || 'https://api.moonshot.ai/v1' : undefined,
    model: provider === 'openai' ? process.env.CTRLB_DESKTOP_OPENAI_MODEL : process.env.MOONSHOT_MODEL || 'kimi-k3',
    frameDataById,
  })(payload.request);
  return response.status(result.ok ? 200 : result.error?.category === 'authentication' ? 503 : 502).json(result);
}
