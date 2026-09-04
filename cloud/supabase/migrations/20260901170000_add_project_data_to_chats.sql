-- Add project_data JSONB to chats table
ALTER TABLE public.chats ADD COLUMN project_data JSONB;

-- Drop policies temporarily since they depend on user_id type
DROP POLICY IF EXISTS "Users can delete their own chats" ON public.chats;
DROP POLICY IF EXISTS "Users can insert their own chats" ON public.chats;
DROP POLICY IF EXISTS "Users can update their own chats" ON public.chats;
DROP POLICY IF EXISTS "Users can view their own chats" ON public.chats;

DROP POLICY IF EXISTS "Users can delete checkpoints of their chats" ON public.checkpoints;
DROP POLICY IF EXISTS "Users can insert checkpoints to their chats" ON public.checkpoints;
DROP POLICY IF EXISTS "Users can update checkpoints of their chats" ON public.checkpoints;
DROP POLICY IF EXISTS "Users can view checkpoints of their chats" ON public.checkpoints;

-- Allow Clerk IDs in user_id by changing it to TEXT and dropping the Supabase auth.users constraint
ALTER TABLE public.chats DROP CONSTRAINT IF EXISTS chats_user_id_fkey;
ALTER TABLE public.chats ALTER COLUMN user_id TYPE TEXT USING user_id::text;
ALTER TABLE public.chats ALTER COLUMN user_id TYPE TEXT USING user_id::text;

-- Recreate policies casting auth.uid() to text
CREATE POLICY "Users can delete their own chats" ON public.chats FOR DELETE TO authenticated USING (auth.uid()::text = user_id);
CREATE POLICY "Users can insert their own chats" ON public.chats FOR INSERT TO authenticated WITH CHECK (auth.uid()::text = user_id);
CREATE POLICY "Users can update their own chats" ON public.chats FOR UPDATE TO authenticated USING (auth.uid()::text = user_id);
CREATE POLICY "Users can view their own chats" ON public.chats FOR SELECT TO authenticated USING (auth.uid()::text = user_id);

CREATE POLICY "Users can delete checkpoints of their chats" ON public.checkpoints FOR DELETE TO authenticated USING (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()::text));
CREATE POLICY "Users can insert checkpoints to their chats" ON public.checkpoints FOR INSERT TO authenticated WITH CHECK (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()::text));
CREATE POLICY "Users can update checkpoints of their chats" ON public.checkpoints FOR UPDATE TO authenticated USING (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()::text));
CREATE POLICY "Users can view checkpoints of their chats" ON public.checkpoints FOR SELECT TO authenticated USING (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()::text));
