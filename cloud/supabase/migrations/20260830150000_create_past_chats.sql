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

-- Create assets table
CREATE TYPE asset_type AS ENUM ('transcript', 'video_clip', 'thumbnail', 'audio_stub');

CREATE TABLE public.assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id UUID NOT NULL REFERENCES public.chats(id) ON DELETE CASCADE,
    checkpoint_id UUID REFERENCES public.checkpoints(id) ON DELETE CASCADE,
    type asset_type NOT NULL,
    storage_url TEXT NOT NULL,
    storage_object_path TEXT, -- Used to delete the object from Supabase Storage
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Function to clean up old chats and their storage objects
CREATE OR REPLACE FUNCTION public.cleanup_old_chats()
RETURNS trigger AS $$
BEGIN
    -- This function deletes chats beyond the 20 most recent ones for the user.
    -- First, delete the related storage objects
    DELETE FROM storage.objects
    WHERE name IN (
        SELECT storage_object_path
        FROM public.assets
        WHERE chat_id IN (
            SELECT id FROM public.chats
            WHERE user_id = NEW.user_id
            ORDER BY created_at DESC
            OFFSET 20
        )
        AND storage_object_path IS NOT NULL
    );

    -- Then, delete the chats (which will cascade to checkpoints and assets)
    DELETE FROM public.chats
    WHERE user_id = NEW.user_id
    AND id IN (
        SELECT id FROM public.chats
        WHERE user_id = NEW.user_id
        ORDER BY created_at DESC
        OFFSET 20
    );
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Trigger to run the cleanup after every new chat is inserted
CREATE TRIGGER enforce_chat_limit
AFTER INSERT ON public.chats
FOR EACH ROW
EXECUTE FUNCTION public.cleanup_old_chats();

-- RLS Policies
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.checkpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.assets ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view their own chats" ON public.chats FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can insert their own chats" ON public.chats FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update their own chats" ON public.chats FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete their own chats" ON public.chats FOR DELETE USING (auth.uid() = user_id);

CREATE POLICY "Users can view checkpoints of their chats" ON public.checkpoints FOR SELECT USING (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));
CREATE POLICY "Users can insert checkpoints to their chats" ON public.checkpoints FOR INSERT WITH CHECK (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));
CREATE POLICY "Users can update checkpoints of their chats" ON public.checkpoints FOR UPDATE USING (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));
CREATE POLICY "Users can delete checkpoints of their chats" ON public.checkpoints FOR DELETE USING (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));

CREATE POLICY "Users can view assets of their chats" ON public.assets FOR SELECT USING (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));
CREATE POLICY "Users can insert assets to their chats" ON public.assets FOR INSERT WITH CHECK (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));
CREATE POLICY "Users can update assets of their chats" ON public.assets FOR UPDATE USING (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));
CREATE POLICY "Users can delete assets of their chats" ON public.assets FOR DELETE USING (EXISTS (SELECT 1 FROM public.chats WHERE id = chat_id AND user_id = auth.uid()));
