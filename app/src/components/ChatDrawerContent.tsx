import React, { useState, useEffect, useCallback } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Image, Alert, RefreshControl, DeviceEventEmitter } from 'react-native';
import { DrawerContentScrollView, DrawerContentComponentProps } from '@react-navigation/drawer';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuthStore } from '../store/authStore';
import { supabase } from '../utils/supabase';
import { GoogleSignin } from '@react-native-google-signin/google-signin';

const getReadableError = (error: unknown): string => {
  if (!error) return 'Unknown error';

  if (error instanceof Error) {
    const anyError = error as Error & { code?: string; status?: number; details?: string; hint?: string };
    const parts = [
      anyError.code ? `code=${anyError.code}` : null,
      typeof anyError.status === 'number' ? `status=${anyError.status}` : null,
      anyError.message ? `message=${anyError.message}` : null,
      anyError.details ? `details=${anyError.details}` : null,
      anyError.hint ? `hint=${anyError.hint}` : null,
    ].filter(Boolean);

    return parts.length > 0 ? parts.join('\n') : 'Unknown error';
  }

  if (typeof error === 'object') {
    try {
      return JSON.stringify(error, null, 2);
    } catch {
      return String(error);
    }
  }

  return String(error);
};

export function ChatDrawerContent(props: DrawerContentComponentProps) {
  const { user, signOut } = useAuthStore();
  const [showSignOut, setShowSignOut] = useState(false);
  const insets = useSafeAreaInsets();
  
  const [pastChats, setPastChats] = useState<{id: string, title: string}[]>([]);
  const [refreshing, setRefreshing] = useState(false);

  const fetchChats = useCallback(async () => {
    if (!user) {
      setPastChats([]);
      return;
    }
    try {
      const { data, error } = await supabase
        .from('chats')
        .select('id, title')
        .eq('user_id', user.id)
        .order('updated_at', { ascending: false });
        
      if (error) throw error;
      if (data) {
        setPastChats(data);
      }
    } catch (e) {
      console.error("Error fetching chats", e);
    }
  }, [user]);

  useEffect(() => {
    fetchChats();
  }, [fetchChats]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await fetchChats();
    setRefreshing(false);
  }, [fetchChats]);

  const handleSignIn = async () => {
    try {
      await GoogleSignin.hasPlayServices();
      const userInfo = await GoogleSignin.signIn();
      if (userInfo.data?.idToken) {
        const { error } = await supabase.auth.signInWithIdToken({
          provider: 'google',
          token: userInfo.data.idToken,
        });
        if (error) throw error;
      } else {
        throw new Error('no ID token present!');
      }
    } catch (error: unknown) {
      const details = getReadableError(error);
      console.error('Google sign-in failed:', details, error);
      Alert.alert('Sign in failed', details);
    }
  };

  const name = user?.user_metadata?.full_name || user?.user_metadata?.name || 'User';
  const avatarUrl = user?.user_metadata?.avatar_url;

  return (
    <View style={[styles.container, { paddingTop: insets.top, paddingBottom: insets.bottom }]}>
      <View style={styles.header}>
        <Text style={styles.title}>History</Text>
        <TouchableOpacity style={styles.newChatBtnContainer} onPress={() => {/* new chat */}}>
          <Text style={styles.newChatBtn}>+</Text>
        </TouchableOpacity>
      </View>

      <DrawerContentScrollView 
        {...props} 
        style={styles.scrollArea} 
        contentContainerStyle={{ paddingTop: 0 }}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            tintColor="#A1A1AA"
            colors={['#09090B']}
            progressBackgroundColor="#FAFAFA"
          />
        }
      >
        <Text style={styles.sectionTitle}>Studio Actions</Text>
        <View style={styles.actionRow}>
          <TouchableOpacity 
            style={styles.actionBtn} 
            onPress={() => { DeviceEventEmitter.emit('togglePreview'); props.navigation.closeDrawer(); }}
          >
            <Text style={styles.actionBtnText}>Toggle Preview</Text>
          </TouchableOpacity>
          <TouchableOpacity 
            style={styles.actionBtn} 
            onPress={() => { DeviceEventEmitter.emit('triggerExport'); props.navigation.closeDrawer(); }}
          >
            <Text style={styles.actionBtnText}>Export Video</Text>
          </TouchableOpacity>
        </View>

        <Text style={styles.sectionTitle}>Past Chats</Text>
        
        {pastChats.map((chat) => (
          <View key={chat.id} style={styles.chatRow}>
            <TouchableOpacity style={styles.chatTitleBtn}>
              <Text style={styles.chatTitle}>{chat.title}</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => {/* delete */}} style={styles.deleteBtn}>
              <Text style={styles.deleteIcon}>✕</Text>
            </TouchableOpacity>
          </View>
        ))}
      </DrawerContentScrollView>

      <View style={styles.divider} />
      
      <View style={styles.accountControl}>
        {!user ? (
          <TouchableOpacity onPress={handleSignIn} style={styles.authBtn}>
            <Text style={styles.authBtnText}>Sign in</Text>
          </TouchableOpacity>
        ) : (
          <View>
            <TouchableOpacity 
              style={styles.profileRow}
              onPress={() => setShowSignOut(!showSignOut)}
            >
              {avatarUrl ? (
                <Image source={{ uri: avatarUrl }} style={styles.avatar} />
              ) : (
                <View style={styles.avatarPlaceholder} />
              )}
              <Text style={styles.name}>{name}</Text>
            </TouchableOpacity>
            
            {showSignOut && (
              <View style={styles.signOutMenu}>
                <Text style={styles.emailText}>{user.email}</Text>
                <TouchableOpacity onPress={() => { signOut(); setShowSignOut(false); }} style={styles.signOutBtn}>
                  <Text style={styles.signOutBtnText}>Sign out</Text>
                </TouchableOpacity>
              </View>
            )}
          </View>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#09090B',
  },
  header: {
    paddingHorizontal: 16,
    paddingTop: 0,
    paddingBottom: 12,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  title: {
    color: '#FAFAFA',
    fontSize: 20,
    fontWeight: '600',
    letterSpacing: -0.5,
  },
  newChatBtnContainer: {
    padding: 4,
  },
  newChatBtn: {
    color: '#FAFAFA',
    fontSize: 24,
    fontWeight: '300',
  },
  scrollArea: {
    flex: 1,
    paddingHorizontal: 16,
  },
  sectionTitle: {
    color: '#71717A',
    fontSize: 12,
    fontWeight: '600',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginTop: 12,
    marginBottom: 8,
  },
  actionRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 16,
    marginHorizontal: -4,
  },
  actionBtn: {
    flex: 1,
    backgroundColor: '#18181B',
    paddingVertical: 10,
    marginHorizontal: 4,
    borderRadius: 8,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#27272A',
  },
  actionBtnText: {
    color: '#FAFAFA',
    fontSize: 14,
    fontWeight: '600',
  },
  chatRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginVertical: 8,
    paddingVertical: 4,
  },
  chatTitleBtn: {
    flex: 1,
  },
  chatTitle: {
    color: '#D4D4D8',
    fontSize: 14,
    fontWeight: '500',
  },
  deleteBtn: {
    padding: 4,
  },
  deleteIcon: {
    color: '#71717A',
    fontSize: 14,
    fontWeight: 'bold',
  },
  divider: {
    height: 1,
    backgroundColor: '#27272A',
    marginHorizontal: 16,
  },
  accountControl: {
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 0,
  },
  authBtn: {
    backgroundColor: '#FAFAFA',
    paddingVertical: 12,
    borderRadius: 24,
    alignItems: 'center',
  },
  authBtnText: {
    color: '#09090B',
    fontSize: 14,
    fontWeight: 'bold',
  },
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  avatar: {
    width: 36,
    height: 36,
    borderRadius: 18,
    marginRight: 12,
  },
  avatarPlaceholder: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#3F3F46',
    marginRight: 12,
  },
  name: {
    color: '#FAFAFA',
    fontSize: 14,
    fontWeight: '600',
  },
  signOutMenu: {
    marginTop: 16,
    backgroundColor: '#18181B',
    padding: 12,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#27272A',
  },
  emailText: {
    color: '#A1A1AA',
    fontSize: 12,
    marginBottom: 12,
  },
  signOutBtn: {
    backgroundColor: '#EF4444',
    paddingVertical: 8,
    borderRadius: 6,
    alignItems: 'center',
  },
  signOutBtnText: {
    color: '#FAFAFA',
    fontSize: 14,
    fontWeight: '600',
  }
});
