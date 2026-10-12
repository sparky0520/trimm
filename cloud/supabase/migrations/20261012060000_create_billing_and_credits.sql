-- Create user_credits table to track balance and lifetime metrics
CREATE TABLE IF NOT EXISTS public.user_credits (
    user_id TEXT PRIMARY KEY,
    balance INTEGER NOT NULL DEFAULT 50 CHECK (balance >= 0),
    lifetime_purchased INTEGER NOT NULL DEFAULT 0 CHECK (lifetime_purchased >= 0),
    lifetime_spent INTEGER NOT NULL DEFAULT 0 CHECK (lifetime_spent >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Create credit_transactions ledger table for auditability and idempotency
CREATE TABLE IF NOT EXISTS public.credit_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    amount INTEGER NOT NULL,
    balance_after INTEGER NOT NULL CHECK (balance_after >= 0),
    type TEXT NOT NULL, -- 'initial_grant', 'purchase', 'chat_completion', 'bgm_generation', 'transcription', 'sfx_fetch', 'refund', 'admin_adjustment'
    description TEXT,
    reference_id TEXT, -- e.g. RevenueCat event ID, transaction ID, or idempotency key
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for fast query performance following Postgres best practices
CREATE INDEX IF NOT EXISTS idx_credit_transactions_user_created 
    ON public.credit_transactions (user_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_credit_transactions_reference 
    ON public.credit_transactions (reference_id) 
    WHERE reference_id IS NOT NULL;

-- Enable Row Level Security (RLS)
ALTER TABLE public.user_credits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.credit_transactions ENABLE ROW LEVEL SECURITY;

-- RLS Policies: Authenticated users can view their own credits and ledger
CREATE POLICY "Users can view own credits" ON public.user_credits
    FOR SELECT TO authenticated
    USING ((select auth.uid())::text = user_id);

CREATE POLICY "Users can view own transactions" ON public.credit_transactions
    FOR SELECT TO authenticated
    USING ((select auth.uid())::text = user_id);

-- Atomic stored procedure: Get or initialize user credits (grants 50 starter credits)
CREATE OR REPLACE FUNCTION public.get_or_create_user_credits(p_user_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_record public.user_credits%ROWTYPE;
BEGIN
    SELECT * INTO v_record FROM public.user_credits WHERE user_id = p_user_id;
    
    IF NOT FOUND THEN
        INSERT INTO public.user_credits (user_id, balance, lifetime_purchased, lifetime_spent)
        VALUES (p_user_id, 50, 0, 0)
        ON CONFLICT (user_id) DO NOTHING
        RETURNING * INTO v_record;
        
        IF FOUND THEN
            INSERT INTO public.credit_transactions (user_id, amount, balance_after, type, description)
            VALUES (p_user_id, 50, 50, 'initial_grant', 'Welcome bonus credits');
        ELSE
            SELECT * INTO v_record FROM public.user_credits WHERE user_id = p_user_id;
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'user_id', v_record.user_id,
        'balance', v_record.balance,
        'lifetime_purchased', v_record.lifetime_purchased,
        'lifetime_spent', v_record.lifetime_spent,
        'updated_at', v_record.updated_at
    );
END;
$$;

-- Atomic stored procedure: Deduct credits with concurrency lock (SELECT FOR UPDATE)
CREATE OR REPLACE FUNCTION public.deduct_user_credits(
    p_user_id TEXT,
    p_amount INTEGER,
    p_type TEXT,
    p_description TEXT,
    p_reference_id TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_record public.user_credits%ROWTYPE;
    v_new_balance INTEGER;
BEGIN
    IF p_amount <= 0 THEN
        RETURN jsonb_build_object('success', false, 'error', 'invalid_amount');
    END IF;

    -- Ensure record exists
    PERFORM public.get_or_create_user_credits(p_user_id);

    -- Lock row for concurrency safety
    SELECT * INTO v_record
    FROM public.user_credits
    WHERE user_id = p_user_id
    FOR UPDATE;

    IF v_record.balance < p_amount THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'insufficient_credits',
            'balance', v_record.balance,
            'required', p_amount
        );
    END IF;

    v_new_balance := v_record.balance - p_amount;

    UPDATE public.user_credits
    SET balance = v_new_balance,
        lifetime_spent = v_record.lifetime_spent + p_amount,
        updated_at = NOW()
    WHERE user_id = p_user_id;

    INSERT INTO public.credit_transactions (
        user_id,
        amount,
        balance_after,
        type,
        description,
        reference_id
    ) VALUES (
        p_user_id,
        -p_amount,
        v_new_balance,
        p_type,
        p_description,
        p_reference_id
    );

    RETURN jsonb_build_object(
        'success', true,
        'balance', v_new_balance,
        'deducted', p_amount
    );
END;
$$;

-- Atomic stored procedure: Add credits with idempotency check on p_reference_id
CREATE OR REPLACE FUNCTION public.add_user_credits(
    p_user_id TEXT,
    p_amount INTEGER,
    p_type TEXT,
    p_description TEXT,
    p_reference_id TEXT DEFAULT NULL,
    p_metadata JSONB DEFAULT '{}'::jsonb
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_record public.user_credits%ROWTYPE;
    v_new_balance INTEGER;
BEGIN
    IF p_amount <= 0 THEN
        RETURN jsonb_build_object('success', false, 'error', 'invalid_amount');
    END IF;

    -- Check idempotency if reference_id is provided
    IF p_reference_id IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM public.credit_transactions WHERE reference_id = p_reference_id) THEN
            SELECT * INTO v_record FROM public.user_credits WHERE user_id = p_user_id;
            RETURN jsonb_build_object(
                'success', true,
                'already_processed', true,
                'balance', COALESCE(v_record.balance, 0)
            );
        END IF;
    END IF;

    -- Ensure record exists
    PERFORM public.get_or_create_user_credits(p_user_id);

    -- Lock row for concurrency safety
    SELECT * INTO v_record
    FROM public.user_credits
    WHERE user_id = p_user_id
    FOR UPDATE;

    v_new_balance := v_record.balance + p_amount;

    UPDATE public.user_credits
    SET balance = v_new_balance,
        lifetime_purchased = v_record.lifetime_purchased + p_amount,
        updated_at = NOW()
    WHERE user_id = p_user_id;

    INSERT INTO public.credit_transactions (
        user_id,
        amount,
        balance_after,
        type,
        description,
        reference_id,
        metadata
    ) VALUES (
        p_user_id,
        p_amount,
        v_new_balance,
        p_type,
        p_description,
        p_reference_id,
        p_metadata
    );

    RETURN jsonb_build_object(
        'success', true,
        'balance', v_new_balance,
        'added', p_amount
    );
END;
$$;
