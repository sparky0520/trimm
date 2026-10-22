import { createClient } from '@supabase/supabase-js';

export const CREDIT_COSTS = Object.freeze({
  CHAT_COMPLETION: 2,
  BGM_GENERATION: 5,
  TRANSCRIPTION: 3,
  SFX_FETCH: 1,
  VISUAL_ANALYSIS_FRAME_DIVISOR: 5, // 1 credit per 5 frames, minimum 1 credit
});

export function calculateVisualAnalysisCredits(frameCount) {
  if (!frameCount || frameCount <= 0) return 0;
  return Math.max(1, Math.ceil(frameCount / CREDIT_COSTS.VISUAL_ANALYSIS_FRAME_DIVISOR));
}

export const CREDIT_PACKAGES = Object.freeze({
  'ai.ctrlb.credits.100': { credits: 100, name: 'Starter Pack' },
  'ai.ctrlb.credits.300': { credits: 300, name: 'Creator Pack' },
  'ai.ctrlb.credits.1000': { credits: 1000, name: 'Pro Studio Pack' },
});

export class BillingService {
  constructor(options = {}) {
    this.supabaseUrl = options.supabaseUrl || process.env.SUPABASE_URL || '';
    this.supabaseSecretKey = options.supabaseSecretKey || process.env.SUPABASE_SECRET_KEY || '';
    this.webhookSecret = options.webhookSecret || process.env.REVENUECAT_WEBHOOK_AUTH_KEY || '';
    this._client = null;
  }

  getClient() {
    if (!this._client) {
      if (!this.supabaseUrl || !this.supabaseSecretKey) {
        throw Object.assign(new Error('Supabase billing is not configured in environment.'), {
          code: 'billing_unconfigured',
          status: 500,
        });
      }
      this._client = createClient(this.supabaseUrl, this.supabaseSecretKey);
    }
    return this._client;
  }

  async executeWithRetry(operation) {
    let lastError;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const client = this.getClient();
        return await operation(client);
      } catch (err) {
        lastError = err;
        const msg = String(err?.message || '') + ' ' + String(err?.details || '');
        if (attempt === 0 && (msg.includes('fetch failed') || msg.includes('SocketError') || msg.includes('UND_ERR_SOCKET') || msg.includes('other side closed'))) {
          console.warn('[billing] Supabase socket error, recreating client and retrying...', err.message);
          this._client = null;
          continue;
        }
        throw err;
      }
    }
    throw lastError;
  }

  async getCredits(userId) {
    if (!userId) throw Object.assign(new Error('User ID is required.'), { status: 400 });
    if (String(userId).startsWith('development:')) {
      return {
        userId,
        balance: 9999,
        lifetimePurchased: 9999,
        lifetimeSpent: 0,
        costs: CREDIT_COSTS,
      };
    }

    return await this.executeWithRetry(async (client) => {
      const { data, error } = await client.rpc('get_or_create_user_credits', {
        p_user_id: String(userId),
      });

      if (error) {
        const msg = String(error.message || '') + ' ' + String(error.details || '');
        if (msg.includes('fetch failed') || msg.includes('SocketError') || msg.includes('UND_ERR_SOCKET') || msg.includes('other side closed')) {
          throw error;
        }
        console.error('[billing] get_or_create_user_credits failed:', error);
        throw Object.assign(new Error('Failed to retrieve user credit balance.'), { status: 500 });
      }

      return {
        userId: data.user_id,
        balance: Number(data.balance ?? 0),
        lifetimePurchased: Number(data.lifetime_purchased ?? 0),
        lifetimeSpent: Number(data.lifetime_spent ?? 0),
        costs: CREDIT_COSTS,
      };
    });
  }

  async checkCredits(userId, requiredAmount) {
    const credits = await this.getCredits(userId);
    return {
      sufficient: credits.balance >= requiredAmount,
      balance: credits.balance,
      required: requiredAmount,
    };
  }

  async deductCredits(userId, amount, type, description, referenceId = null) {
    if (!userId) throw Object.assign(new Error('User ID is required.'), { status: 400 });
    if (String(userId).startsWith('development:')) {
      return { success: true, balance: 9999, deducted: amount };
    }
    if (amount <= 0) return { success: true, balance: 0, deducted: 0 };

    return await this.executeWithRetry(async (client) => {
      const { data, error } = await client.rpc('deduct_user_credits', {
        p_user_id: String(userId),
        p_amount: Math.round(amount),
        p_type: String(type),
        p_description: String(description || ''),
        p_reference_id: referenceId ? String(referenceId) : null,
      });

      if (error) {
        const msg = String(error.message || '') + ' ' + String(error.details || '');
        if (msg.includes('fetch failed') || msg.includes('SocketError') || msg.includes('UND_ERR_SOCKET') || msg.includes('other side closed')) {
          throw error;
        }
        console.error('[billing] deduct_user_credits error:', error);
        throw Object.assign(new Error('Credit deduction failed.'), { status: 500 });
      }

      if (!data?.success) {
        const err = new Error(
          data?.error === 'insufficient_credits'
            ? 'You need more credits to continue.'
            : 'Credit deduction declined.'
        );
        err.status = 402;
        err.code = data?.error || 'insufficient_credits';
        err.balance = data?.balance ?? 0;
        err.required = amount;
        throw err;
      }

      return data;
    });
  }

  async addCredits(userId, amount, type, description, referenceId = null, metadata = {}) {
    if (!userId) throw Object.assign(new Error('User ID is required.'), { status: 400 });
    if (amount <= 0) return { success: true, balance: 0, added: 0 };

    return await this.executeWithRetry(async (client) => {
      const { data, error } = await client.rpc('add_user_credits', {
        p_user_id: String(userId),
        p_amount: Math.round(amount),
        p_type: String(type),
        p_description: String(description || ''),
        p_reference_id: referenceId ? String(referenceId) : null,
        p_metadata: metadata || {},
      });

      if (error) {
        const msg = String(error.message || '') + ' ' + String(error.details || '');
        if (msg.includes('fetch failed') || msg.includes('SocketError') || msg.includes('UND_ERR_SOCKET') || msg.includes('other side closed')) {
          throw error;
        }
        console.error('[billing] add_user_credits error:', error);
        throw Object.assign(new Error('Adding credits failed.'), { status: 500 });
      }

      return data;
    });
  }

  async getHistory(userId, limit = 30) {
    if (!userId) throw Object.assign(new Error('User ID is required.'), { status: 400 });

    return await this.executeWithRetry(async (client) => {
      const { data, error } = await client
        .from('credit_transactions')
        .select('id, amount, balance_after, type, description, reference_id, created_at')
        .eq('user_id', String(userId))
        .order('created_at', { ascending: false })
        .limit(Math.min(limit, 100));

      if (error) {
        const msg = String(error.message || '') + ' ' + String(error.details || '');
        if (msg.includes('fetch failed') || msg.includes('SocketError') || msg.includes('UND_ERR_SOCKET') || msg.includes('other side closed')) {
          throw error;
        }
        console.error('[billing] credit_transactions query error:', error);
        throw Object.assign(new Error('Failed to retrieve credit history.'), { status: 500 });
      }

      return data || [];
    });
  }

  resolveCreditPackage(productId) {
    if (!productId) return null;
    const pkg = CREDIT_PACKAGES[productId];
    if (pkg) return pkg;

    // Fallback regex matching e.g. "credits.500" or "pack_100"
    const match = String(productId).match(/(\d+)/);
    if (match) {
      const parsedCredits = parseInt(match[1], 10);
      if (parsedCredits > 0) {
        return { credits: parsedCredits, name: `${parsedCredits} Credits` };
      }
    }
    return null;
  }

  async handleRevenueCatWebhook(headers, body) {
    // Validate authorization header if secret is configured
    if (this.webhookSecret) {
      const authHeader = headers['authorization'] || headers['x-revenuecat-webhook-auth'] || '';
      const token = authHeader.replace(/^Bearer\s+/i, '').trim();
      if (token !== this.webhookSecret) {
        throw Object.assign(new Error('Unauthorized RevenueCat webhook.'), { status: 401 });
      }
    }

    const event = body?.event;
    if (!event) {
      throw Object.assign(new Error('Invalid RevenueCat webhook payload: missing event.'), { status: 400 });
    }

    // Ignore test events cleanly
    if (event.type === 'TEST') {
      return { success: true, type: 'TEST', message: 'Test webhook handled' };
    }

    const appUserId = event.app_user_id;
    if (!appUserId) {
      throw Object.assign(new Error('Missing app_user_id in RevenueCat webhook event.'), { status: 400 });
    }

    // Process purchases (NON_RENEWING_PURCHASE or INITIAL_PURCHASE)
    if (
      event.type === 'NON_RENEWING_PURCHASE' ||
      event.type === 'INITIAL_PURCHASE' ||
      event.type === 'RENEWAL'
    ) {
      const productId = event.product_id;
      const pkg = this.resolveCreditPackage(productId);
      const creditsToAdd = pkg ? pkg.credits : 100;
      const description = pkg ? `Purchased ${pkg.name}` : `Purchased ${productId}`;
      const referenceId = event.id || event.transaction_id || `rc_${Date.now()}`;

      const metadata = {
        productId,
        currency: event.currency,
        priceInPurchasedCurrency: event.price_in_purchased_currency,
        store: event.store,
        environment: event.environment,
        purchasedAtMs: event.purchased_at_ms,
      };

      const result = await this.addCredits(
        appUserId,
        creditsToAdd,
        'purchase',
        description,
        referenceId,
        metadata
      );

      return {
        success: true,
        userId: appUserId,
        creditsAdded: creditsToAdd,
        alreadyProcessed: result.already_processed === true,
        balance: result.balance,
      };
    }

    return {
      success: true,
      ignored: true,
      eventType: event.type,
      message: `Event type ${event.type} does not affect consumable credit balance.`,
    };
  }
}

export const billingService = new BillingService();
