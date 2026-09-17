import React, { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, ActivityIndicator, FlatList } from 'react-native';
import Animated, { FadeInDown, FadeOut, Layout } from 'react-native-reanimated';
import EventSource from 'react-native-sse';
import { useProjectStore } from '../store/projectStore';
import { BACKEND_URL } from '../utils/config';

interface Message {
  id: string;
  role: 'user' | 'ai';
  content: string;
}

export const Chat = () => {
  const presentSchema = useProjectStore(state => state.present);
  const processToolCall = useProjectStore(state => state.processToolCall);
  
  const [messages, setMessages] = useState<Message[]>([
    { id: '1', role: 'ai', content: 'Hello! I am your AI editor. Ask me to add a clip or some text.' }
  ]);
  const [inputText, setInputText] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);

  const sendMessage = () => {
    if (!inputText.trim()) return;

    const userMsg: Message = { id: Date.now().toString(), role: 'user', content: inputText };
    const newHistory = [...messages, userMsg];
    setMessages(newHistory);
    setInputText('');
    setIsProcessing(true);

    const payload = {
      history: newHistory.map(m => ({ role: m.role === 'ai' ? 'assistant' : 'user', content: m.content })),
      projectSchema: presentSchema,
    };

    // The backend runs on localhost:8787
    // In Android emulator, localhost is 10.0.2.2. Assuming iOS simulator here which uses localhost.
    const es = new EventSource(`${BACKEND_URL}/v1/chat/completion`, {
      headers: {
        'Content-Type': 'application/json',
      },
      method: 'POST',
      body: JSON.stringify(payload)
    });

    let aiResponseText = '';

    es.addEventListener('message', (event) => {
      if (!event.data || event.data === '[DONE]') {
        es.close();
        setIsProcessing(false);
        if (aiResponseText.length > 0) {
          setMessages(prev => [...prev, { id: Date.now().toString(), role: 'ai', content: aiResponseText }]);
        }
        return;
      }

      try {
        const chunk = JSON.parse(event.data);
        if (chunk.type === 'text') {
          aiResponseText += chunk.content;
        } else if (chunk.type === 'tool_call') {
          // Immediately dispatch the tool call to Zustand to see UI update in real-time
          processToolCall({ tool: chunk.tool, ...chunk.args });
        }
      } catch (e) {
        console.error('Failed to parse SSE chunk', e);
      }
    });

    es.addEventListener('error', (err) => {
      console.error('SSE Error:', err);
      es.close();
      setIsProcessing(false);
    });
  };

  const renderItem = ({ item }: { item: Message }) => (
    <Animated.View 
      entering={FadeInDown.springify()} 
      exiting={FadeOut} 
      layout={Layout.springify()}
      style={[styles.messageBubble, item.role === 'user' ? styles.userBubble : styles.aiBubble]}
    >
      <Text style={styles.messageText}>{item.content}</Text>
    </Animated.View>
  );

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>AI Agent</Text>
      </View>

      <FlatList
        data={messages}
        keyExtractor={item => item.id}
        renderItem={renderItem}
        contentContainerStyle={styles.messageList}
        inverted={false}
      />

      <View style={styles.inputArea}>
        <TextInput
          style={styles.input}
          placeholder="Ask AI to edit..."
          placeholderTextColor="#666"
          value={inputText}
          onChangeText={setInputText}
          onSubmitEditing={sendMessage}
        />
        <TouchableOpacity style={styles.sendButton} onPress={sendMessage} disabled={isProcessing}>
          {isProcessing ? (
            <ActivityIndicator size="small" color="#FFF" />
          ) : (
            <Text style={styles.sendButtonText}>Send</Text>
          )}
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#1E1E1E', borderTopWidth: 1, borderTopColor: '#333' },
  header: { padding: 8, backgroundColor: '#2A2A2A', borderBottomWidth: 1, borderBottomColor: '#333' },
  title: { color: '#CCC', fontSize: 12, fontWeight: 'bold' },
  messageList: { padding: 16 },
  messageBubble: { padding: 12, borderRadius: 8, marginBottom: 8, maxWidth: '80%' },
  userBubble: { backgroundColor: '#2196F3', alignSelf: 'flex-end', borderBottomRightRadius: 2 },
  aiBubble: { backgroundColor: '#333', alignSelf: 'flex-start', borderBottomLeftRadius: 2 },
  messageText: { color: '#FFF', fontSize: 14 },
  inputArea: { flexDirection: 'row', padding: 8, backgroundColor: '#2A2A2A', alignItems: 'center' },
  input: { flex: 1, backgroundColor: '#111', color: '#FFF', paddingHorizontal: 12, paddingVertical: 8, borderRadius: 20, marginRight: 8 },
  sendButton: { backgroundColor: '#4CAF50', paddingHorizontal: 16, paddingVertical: 8, borderRadius: 20, justifyContent: 'center', alignItems: 'center' },
  sendButtonText: { color: '#FFF', fontWeight: 'bold' },
});
