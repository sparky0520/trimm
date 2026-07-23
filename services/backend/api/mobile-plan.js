import plannerCore from '../server/planner-core.cjs';
import authenticationModule from '../server/authentication.cjs';
import requestPolicy from '../server/request-policy.cjs';

const authentication = authenticationModule.fromEnvironment();

export default async function mobilePlan(request, response) {
  if (request.method !== 'POST') return response.status(405).json({ error: 'Method not allowed' });
  try { request.principal = (await requestPolicy.authorizeRequest(request, authentication, { scope: 'plan:create', action: 'create', resourceType: 'plan' })).principal; }
  catch (error) { return requestPolicy.authorizationResponse(response, error); }
  try { return response.status(200).json(await plannerCore.plan(request.body)); }
  catch (error) {
    console.error('[mobile-planner] request failed', { message: error?.message, status: error?.status });
    return response.status(Number.isInteger(error?.status) ? error.status : 502).json({ error: error?.message || 'Planner failed' });
  }
}
