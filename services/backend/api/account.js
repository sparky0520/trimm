import authenticationModule from '../server/authentication.cjs';
import authorizationModule from '../server/authorization.cjs';
import lifecycleModule from '../server/account-lifecycle.cjs';
import storeModule from '../server/account-store.cjs';

const authentication = authenticationModule.fromEnvironment();
const store = new storeModule.VolatileAccountStore();
let lifecycle;

function service() {
  lifecycle ||= new lifecycleModule.AccountLifecycle({
    identityProvider: lifecycleModule.clerkAdapter({ secretKey: process.env.CLERK_SECRET_KEY }),
    dataStore: store,
    auditStore: { append: async (event) => console.info('[audit]', event) },
  });
  return lifecycle;
}

export default async function handler(request, response) {
  const requestId = authorizationModule.correlationId(request);
  let principal;
  try {
    principal = await authentication.authenticate(request);
    if (principal.authentication === 'development-bootstrap') {
      throw lifecycleModule.lifecycleError('Bootstrap identities cannot own or migrate accounts.', 403, 'bootstrap_account_forbidden');
    }
    const action = String(request.query?.action || '');
    if (request.method === 'GET' && action === 'export') return response.status(200).json(await service().exportAccount(principal));
    if (request.method === 'POST' && action === 'sign-out-all') return response.status(200).json(await service().signOutAll(principal));
    if (request.method === 'DELETE' && action === 'delete') return response.status(202).json(await service().deleteAccount(principal));
    return response.status(404).json({ error: 'Unknown account operation.', code: 'not_found', requestId });
  } catch (error) {
    console.info('[audit]', authorizationModule.auditEvent({ principal, action: `account.${request.query?.action || 'unknown'}`, resourceType: 'account', outcome: 'denied', reason: error?.code, requestId }));
    return response.status(Number.isInteger(error?.status) ? error.status : 503).json({
      error: error?.message || 'Account operation failed.', code: error?.code || 'account_operation_failed', requestId,
    });
  }
}
