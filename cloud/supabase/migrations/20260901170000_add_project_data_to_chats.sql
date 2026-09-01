-- Add project_data JSONB to chats table
ALTER TABLE public.chats ADD COLUMN project_data JSONB;

-- Allow Clerk IDs in user_id by changing it to TEXT and dropping the Supabase auth.users constraint
ALTER TABLE public.chats DROP CONSTRAINT IF EXISTS chats_user_id_fkey;
ALTER TABLE public.chats ALTER COLUMN user_id TYPE TEXT USING user_id::text;
