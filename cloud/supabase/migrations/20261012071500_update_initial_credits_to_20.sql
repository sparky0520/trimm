-- Update default balance on user_credits table to 20
ALTER TABLE public.user_credits ALTER COLUMN balance SET DEFAULT 20;

-- Update stored procedure to grant 20 welcome bonus credits instead of 50
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
        VALUES (p_user_id, 20, 0, 0)
        ON CONFLICT (user_id) DO NOTHING
        RETURNING * INTO v_record;
        
        IF FOUND THEN
            INSERT INTO public.credit_transactions (user_id, amount, balance_after, type, description)
            VALUES (p_user_id, 20, 20, 'initial_grant', 'Welcome bonus credits');
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
