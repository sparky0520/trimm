import { createClient } from '@supabase/supabase-js';
import AsyncStorage from '@react-native-async-storage/async-storage';

export const supabaseURL = 'https://gahrfsmpnakrjmvhikzf.supabase.co';
export const supabaseAnonKey = 'sb_publishable_7xsS4O5zgZlvfkS1bhAgLQ_kXkfTz92'; // Using the publishable key from Secrets.swift

export const supabase = createClient(supabaseURL, supabaseAnonKey, {
  auth: {
    storage: AsyncStorage,
    autoRefreshToken: true,
    persistSession: true,
    detectSessionInUrl: false,
  },
});
