'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { AccountLifecycle, clerkAdapter, deletionId } = require('../server/account-lifecycle.cjs');
const { VolatileAccountStore } = require('../server/account-store.cjs');

const principal = { id: 'user-123', authentication: 'oidc' };

test('account export combines bounded identity and ctrlB-owned data', async () => {
  const lifecycle = new AccountLifecycle({
    identityProvider: { exportUser: async () => ({ id: principal.id }), revokeSessions: async () => {}, deleteUser: async () => {} },
    dataStore: Object.assign(new VolatileAccountStore(), { exportUser: async () => ({ projects: [{ id: 'project-1' }] }) }),
    now: () => new Date('2026-07-23T00:00:00Z'),
  });
  assert.deepEqual(await lifecycle.exportAccount(principal), {
    schemaVersion: 1, exportedAt: '2026-07-23T00:00:00.000Z', userId: 'user-123',
    identity: { id: 'user-123' }, data: { projects: [{ id: 'project-1' }] },
  });
});

test('deletion revokes sessions before remote data and identity and is idempotent', async () => {
  const order = [];
  const store = new VolatileAccountStore();
  store.removeProviderConnections = async () => order.push('connections');
  store.removeUserData = async () => order.push('data');
  const lifecycle = new AccountLifecycle({
    identityProvider: { exportUser: async () => ({}), revokeSessions: async () => order.push('sessions'), deleteUser: async () => order.push('identity') },
    dataStore: store,
  });
  const first = await lifecycle.deleteAccount(principal);
  const second = await lifecycle.deleteAccount(principal);
  assert.deepEqual(order, ['sessions', 'connections', 'data', 'identity']);
  assert.deepEqual(second, first);
  assert.equal(first.operationId, deletionId(principal.id));
});

test('partial deletion records progress and resumes after the failed step', async () => {
  const order = [];
  let fail = true;
  const store = new VolatileAccountStore();
  store.removeProviderConnections = async () => order.push('connections');
  store.removeUserData = async () => { order.push('data'); if (fail) { fail = false; throw new Error('outage'); } };
  const lifecycle = new AccountLifecycle({
    identityProvider: { exportUser: async () => ({}), revokeSessions: async () => order.push('sessions'), deleteUser: async () => order.push('identity') },
    dataStore: store,
  });
  await assert.rejects(lifecycle.deleteAccount(principal), (error) => error.code === 'deletion_incomplete');
  await lifecycle.deleteAccount(principal);
  assert.deepEqual(order, ['sessions', 'connections', 'data', 'data', 'identity']);
});

test('Clerk adapter exports only bounded profile fields and revokes active sessions', async () => {
  const calls = [];
  const fetchImpl = async (url, options = {}) => {
    calls.push([url, options.method || 'GET', options.headers.authorization]);
    if (url.includes('/users/')) return { ok: true, status: 200, json: async () => ({ id: 'user-123', private_metadata: { secret: true }, email_addresses: [{ id: 'email-1', email_address: 'a@test.dev', verification: { status: 'verified' } }], external_accounts: [] }) };
    return { ok: true, status: 200, json: async () => ({ data: [{ id: 'session-1', status: 'active' }, { id: 'session-2', status: 'ended' }] }) };
  };
  const adapter = clerkAdapter({ secretKey: 'server-secret', fetchImpl, baseUrl: 'https://id.test/v1' });
  const exported = await adapter.exportUser('user-123');
  await adapter.revokeSessions('user-123');
  assert.equal(exported.private_metadata, undefined);
  assert.equal(exported.emailAddresses[0].verificationStatus, 'verified');
  assert.equal(calls.filter(([url]) => url.endsWith('/revoke')).length, 1);
  assert.ok(calls.every(([, , auth]) => auth === 'Bearer server-secret'));
});
