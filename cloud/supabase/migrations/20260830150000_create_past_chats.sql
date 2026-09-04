-- Create chats table
CREATE TABLE public.chats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    title TEXT NOT NULL DEFAULT 'New Chat',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Create checkpoints table
CREATE TYPE checkpoint_status AS ENUM ('pending', 'processing', 'completed', 'failed');

CREATE TABLE public.checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id UUID NOT NULL REFERENCES public.chats(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES public.checkpoints(id) ON DELETE SET NULL,
    status checkpoint_status NOT NULL DEFAULT 'pending',
    prompt TEXT,
    edit_instructions JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- RLS Policies
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.checkpoints ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view their own chats" ON public.chats FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can insert their own chats" ON public.chats FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update their own chats" ON public.chats FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete their own chats" ON public.chats FOR DELETE USING (auth.uid() = user_id);

CREATE POLICY "Users can view checkpoints of their chats" ON public.checkpoints FOR SELECT USING (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));
CREATE POLICY "Users can insert checkpoints to their chats" ON public.checkpoints FOR INSERT WITH CHECK (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()));
CREATE POLICY "Users can update checkpoints of their chats" ON public.checkpoints FOR UPDATE USING (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()));
CREATE POLICY "Users can delete checkpoints of their chats" ON public.checkpoints FOR DELETE USING (EXISTS (SELECT 1 FROM chats WHERE chats.id = checkpoints.chat_id AND chats.user_id = auth.uid()));
