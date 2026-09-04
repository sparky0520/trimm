
import crypto from 'node:crypto';

function lifecycleError(message, status, code) {
  return Object.assign(new Error(message), { status, code });
}

function deletionId(userId) {
  return crypto.createHash('sha256').update(`trimm-account-deletion:${userId}`).digest('hex').slice(0, 32);
}

class AccountLifecycle {
  constructor({ identityProvider, dataStore, auditStore, now = () => new Date() } = {}) {
    if (!identityProvider || !dataStore) throw new TypeError('Account lifecycle adapters are required.');
    this.identityProvider = identityProvider;
    this.dataStore = dataStore;
    this.auditStore = auditStore || { append: async () => {} };
    this.now = now;
  }

  async exportAccount(principal) {
    const [identity, data] = await Promise.all([
      this.identityProvider.exportUser(principal.id),
      this.dataStore.exportUser(principal.id),
    ]);
    await this.auditStore.append({
      occurredAt: this.now().toISOString(),
      userId: principal.id,
      action: 'account.export',
      outcome: 'completed',
    });
    return Object.freeze({
      schemaVersion: 1,
      exportedAt: this.now().toISOString(),
      userId: principal.id,
      identity,
      data,
    });
  }

  async signOutAll(principal) {
    await this.identityProvider.revokeSessions(principal.id);
    await this.auditStore.append({
      occurredAt: this.now().toISOString(),
      userId: principal.id,
      action: 'account.sessions.revoke',
      outcome: 'completed',
    });
    return { revoked: true };
  }

  async deleteAccount(principal) {
    const operationId = deletionId(principal.id);
    const state = await this.dataStore.beginDeletion({
      operationId,
      userId: principal.id,
      requestedAt: this.now().toISOString(),
    });
    if (state?.status === 'completed') return { operationId, status: 'completed' };

    const completed = new Set(state?.completedSteps || []);
    const run = async (step, action) => {
      if (completed.has(step)) return;
      try {
        await action();
        completed.add(step);
        await this.dataStore.recordDeletionStep(operationId, step);
      } catch (cause) {
        await this.dataStore.failDeletion(operationId, step);
        throw lifecycleError('Account deletion is incomplete and will require a retry.', 503, 'deletion_incomplete');
      }
    };

    await run('sessions_revoked', () => this.identityProvider.revokeSessions(principal.id));
    await run('connections_removed', () => this.dataStore.removeProviderConnections(principal.id));
    await run('remote_data_removed', () => this.dataStore.removeUserData(principal.id));
    await run('identity_deleted', () => this.identityProvider.deleteUser(principal.id));
    await this.dataStore.completeDeletion(operationId, this.now().toISOString());
    await this.auditStore.append({
      occurredAt: this.now().toISOString(),
      userId: principal.id,
      action: 'account.delete',
      outcome: 'completed',
      operationId,
    });
    return { operationId, status: 'completed' };
  }
}

function clerkAdapter({ secretKey, fetchImpl = global.fetch, baseUrl = 'https://api.clerk.com/v1' } = {}) {
  if (!secretKey) throw lifecycleError('Account lifecycle is not configured.', 503, 'lifecycle_unconfigured');
  const request = async (path, options = {}) => {
    const response = await fetchImpl(`${baseUrl}${path}`, {
      ...options,
      headers: { authorization: `Bearer ${secretKey}`, 'content-type': 'application/json', ...options.headers },
    });
    if (!response.ok) throw new Error(`Identity provider request failed (${response.status}).`);
    return response.status === 204 ? null : response.json();
  };
  return {
    exportUser: async (userId) => {
      const user = await request(`/users/${encodeURIComponent(userId)}`);
      return {
        id: user.id,
        createdAt: user.created_at,
        updatedAt: user.updated_at,
        primaryEmailAddressId: user.primary_email_address_id,
        emailAddresses: (user.email_addresses || []).map(({ id, email_address, verification }) => ({
          id, emailAddress: email_address, verificationStatus: verification?.status || null,
        })),
        externalAccounts: (user.external_accounts || []).map(({ id, provider, provider_user_id }) => ({
          id, provider, providerUserId: provider_user_id,
        })),
      };
    },
    revokeSessions: async (userId) => {
      const sessions = await request(`/sessions?user_id=${encodeURIComponent(userId)}&limit=100`);
      for (const session of sessions.data || []) {
        if (session.status === 'active') await request(`/sessions/${encodeURIComponent(session.id)}/revoke`, { method: 'POST' });
      }
    },
    deleteUser: (userId) => request(`/users/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
  };
}

export {  AccountLifecycle, clerkAdapter, deletionId, lifecycleError  };
