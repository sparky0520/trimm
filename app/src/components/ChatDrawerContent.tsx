import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Image, Alert } from 'react-native';
import { DrawerContentScrollView, DrawerContentComponentProps } from '@react-navigation/drawer';
import { useAuthStore } from '../store/authStore';
import { supabase } from '../utils/supabase';
import { GoogleSignin } from '@react-native-google-signin/google-signin';

export function ChatDrawerContent(props: DrawerContentComponentProps) {
  const { user, signOut } = useAuthStore();
  const [showSignOut, setShowSignOut] = useState(false);
  
  // Dummy past chats for now, just to match the UI
  const pastChats = [
    { id: '1', title: 'Editing Session 1' },
    { id: '2', title: 'Project Trimm' }
  ];

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
    } catch (error: any) {
      console.error(error);
      Alert.alert('Sign in failed', error.message);
    }
  };

  const name = user?.user_metadata?.full_name || user?.user_metadata?.name || 'User';
  const avatarUrl = user?.user_metadata?.avatar_url;

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Trimm</Text>
        <TouchableOpacity onPress={() => {/* new chat */}}>
          <Text style={styles.newChatBtn}>+</Text>
        </TouchableOpacity>
      </View>

      <DrawerContentScrollView {...props} style={styles.scrollArea}>
        <Text style={styles.sectionTitle}>Past Chats</Text>
        
        {pastChats.map((chat) => (
          <View key={chat.id} style={styles.chatRow}>
            <TouchableOpacity style={styles.chatTitleBtn}>
              <Text style={styles.chatTitle}>{chat.title}</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => {/* delete */}}>
              <Text style={styles.deleteIcon}>🗑️</Text>
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
    backgroundColor: '#171717',
  },
  header: {
    padding: 16,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 40, // safe area approximation
  },
  title: {
    color: '#FAFAFA',
    fontSize: 24,
    fontWeight: '600',
    letterSpacing: -1,
  },
  newChatBtn: {
    color: '#FAFAFA',
    fontSize: 24,
  },
  scrollArea: {
    flex: 1,
    paddingHorizontal: 16,
  },
  sectionTitle: {
    color: 'gray',
    fontSize: 12,
    marginBottom: 8,
  },
  chatRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginVertical: 8,
  },
  chatTitleBtn: {
    flex: 1,
  },
  chatTitle: {
    color: '#FAFAFA',
    fontSize: 14,
  },
  deleteIcon: {
    fontSize: 16,
  },
  divider: {
    height: 1,
    backgroundColor: '#3F3F46',
    marginVertical: 8,
  },
  accountControl: {
    padding: 16,
    marginBottom: 20,
  },
  authBtn: {
    paddingVertical: 8,
  },
  authBtnText: {
    color: '#FAFAFA',
    fontSize: 14,
  },
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  avatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    marginRight: 12,
  },
  avatarPlaceholder: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: '#3F3F46',
    marginRight: 12,
  },
  name: {
    color: '#FAFAFA',
    fontSize: 14,
    fontWeight: '500',
  },
  signOutMenu: {
    marginTop: 12,
    backgroundColor: '#27272A',
    padding: 12,
    borderRadius: 8,
  },
  emailText: {
    color: '#A1A1AA',
    fontSize: 12,
    marginBottom: 8,
  },
  signOutBtn: {
    paddingVertical: 4,
  },
  signOutBtnText: {
    color: '#FAFAFA',
    fontSize: 14,
  }
});
