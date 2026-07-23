import plannerCore from '../server/planner-core.cjs';
import authenticationModule from '../server/authentication.cjs';

const authentication = authenticationModule.fromEnvironment();

export default async function mobilePlan(request, response) {
  if (request.method !== 'POST') return response.status(405).json({ error: 'Method not allowed' });
  try { request.principal = await authentication.authenticate(request); }
  catch (error) { return response.status(Number.isInteger(error?.status) ? error.status : 401).json({ error: error?.message || 'Unauthorized', code: error?.code || 'unauthorized' }); }
  try { return response.status(200).json(await plannerCore.plan(request.body)); }
  catch (error) {
    console.error('[mobile-planner] request failed', { message: error?.message, status: error?.status });
    return response.status(Number.isInteger(error?.status) ? error.status : 502).json({ error: error?.message || 'Planner failed' });
  }
}
