import plannerCore from '../server/planner-core.cjs';

export default async function mobilePlan(request, response) {
  if (request.method !== 'POST') return response.status(405).json({ error: 'Method not allowed' });
  if (!plannerCore.authorized(request.headers.authorization)) return response.status(401).json({ error: 'Unauthorized' });
  try { return response.status(200).json(await plannerCore.plan(request.body)); }
  catch (error) {
    console.error('[mobile-planner] request failed', { message: error?.message, status: error?.status });
    return response.status(Number.isInteger(error?.status) ? error.status : 502).json({ error: error?.message || 'Planner failed' });
  }
}
