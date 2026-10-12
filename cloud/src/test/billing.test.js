import test from 'node:test';
import assert from 'node:assert/strict';
import { BillingService, CREDIT_COSTS, CREDIT_PACKAGES } from '../services/billing.js';

test('credit costs define required amounts for AI features', () => {
  assert.equal(CREDIT_COSTS.CHAT_COMPLETION, 2);
  assert.equal(CREDIT_COSTS.BGM_GENERATION, 5);
  assert.equal(CREDIT_COSTS.TRANSCRIPTION, 3);
  assert.equal(CREDIT_COSTS.SFX_FETCH, 1);
});

test('credit package resolver identifies known and numbered packages', () => {
  const service = new BillingService({ supabaseUrl: 'http://mock', supabaseSecretKey: 'mock' });
  
  const starter = service.resolveCreditPackage('ai.ctrlb.credits.100');
  assert.equal(starter.credits, 100);
  assert.equal(starter.name, 'Starter Pack');

  const creator = service.resolveCreditPackage('ai.ctrlb.credits.300');
  assert.equal(creator.credits, 300);

  const studio = service.resolveCreditPackage('ai.ctrlb.credits.1000');
  assert.equal(studio.credits, 1000);

  const fallback = service.resolveCreditPackage('credits.500');
  assert.equal(fallback.credits, 500);

  const invalid = service.resolveCreditPackage('unknown_non_number');
  assert.equal(invalid, null);
});

test('RevenueCat webhook handles TEST events gracefully', async () => {
  const service = new BillingService({ supabaseUrl: 'http://mock', supabaseSecretKey: 'mock' });
  const result = await service.handleRevenueCatWebhook({}, {
    event: { type: 'TEST' }
  });
  assert.equal(result.success, true);
  assert.equal(result.type, 'TEST');
});

test('RevenueCat webhook verifies auth header when secret is configured', async () => {
  const service = new BillingService({
    supabaseUrl: 'http://mock',
    supabaseSecretKey: 'mock',
    webhookSecret: 'top-secret-token'
  });

  await assert.rejects(
    async () => {
      await service.handleRevenueCatWebhook(
        { authorization: 'Bearer wrong-token' },
        { event: { type: 'TEST' } }
      );
    },
    (err) => {
      assert.equal(err.status, 401);
      return true;
    }
  );

  const result = await service.handleRevenueCatWebhook(
    { authorization: 'Bearer top-secret-token' },
    { event: { type: 'TEST' } }
  );
  assert.equal(result.success, true);
});

test('RevenueCat webhook processes NON_RENEWING_PURCHASE and credits user', async () => {
  let rpcCalledWith = null;
  const mockClient = {
    rpc: async (func, args) => {
      rpcCalledWith = { func, args };
      return { data: { success: true, balance: 150, added: args.p_amount } };
    }
  };

  const service = new BillingService({
    supabaseUrl: 'http://mock',
    supabaseSecretKey: 'mock'
  });
  service._client = mockClient;

  const result = await service.handleRevenueCatWebhook({}, {
    event: {
      id: 'rc_evt_12345',
      type: 'NON_RENEWING_PURCHASE',
      app_user_id: 'user_test_uuid',
      product_id: 'ai.ctrlb.credits.100',
      currency: 'USD',
      price_in_purchased_currency: 4.99,
      store: 'APP_STORE',
      environment: 'SANDBOX',
      purchased_at_ms: Date.now()
    }
  });

  assert.equal(result.success, true);
  assert.equal(result.userId, 'user_test_uuid');
  assert.equal(result.creditsAdded, 100);
  assert.equal(rpcCalledWith.func, 'add_user_credits');
  assert.equal(rpcCalledWith.args.p_user_id, 'user_test_uuid');
  assert.equal(rpcCalledWith.args.p_amount, 100);
  assert.equal(rpcCalledWith.args.p_reference_id, 'rc_evt_12345');
});

test('deductCredits throws 402 with insufficient_credits on low balance', async () => {
  const mockClient = {
    rpc: async (func, args) => {
      if (func === 'get_or_create_user_credits') {
        return { data: { balance: 1 } };
      }
      return {
        data: {
          success: false,
          error: 'insufficient_credits',
          balance: 1,
          required: 5
        }
      };
    }
  };

  const service = new BillingService({
    supabaseUrl: 'http://mock',
    supabaseSecretKey: 'mock'
  });
  service._client = mockClient;

  await assert.rejects(
    async () => {
      await service.deductCredits('user_low_balance', 5, 'bgm_generation', 'Test BGM');
    },
    (err) => {
      assert.equal(err.status, 402);
      assert.equal(err.code, 'insufficient_credits');
      assert.equal(err.balance, 1);
      assert.equal(err.required, 5);
      return true;
    }
  );
});
