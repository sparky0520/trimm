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

  const renderItem = ({ item }: { item: Message }) => {
    if (item.role === 'user') {
      return (
        <Animated.View 
          entering={FadeInDown.springify()} 
          exiting={FadeOut} 
          layout={Layout.springify()}
          style={styles.userBubbleWrapper}
        >
          <View style={styles.userBubble}>
            <Text style={styles.userMessageText}>{item.content}</Text>
          </View>
        </Animated.View>
      );
    } else {
      return (
        <Animated.View 
          entering={FadeInDown.springify()} 
          exiting={FadeOut} 
          layout={Layout.springify()}
          style={styles.aiBubbleWrapper}
        >
          <View style={styles.aiBubble}>
            <Text style={styles.aiTitle}>Trimm</Text>
            <Text style={styles.aiMessageText}>{item.content}</Text>
          </View>
        </Animated.View>
      );
    }
  };

  return (
    <View style={styles.container}>
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
          placeholderTextColor="#A1A1AA"
          value={inputText}
          onChangeText={setInputText}
          onSubmitEditing={sendMessage}
        />
        <TouchableOpacity style={styles.sendButton} onPress={sendMessage} disabled={isProcessing}>
          {isProcessing ? (
            <ActivityIndicator size="small" color="#09090B" />
          ) : (
            <Text style={styles.sendButtonText}>Send</Text>
          )}
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#09090B' },
  messageList: { padding: 16 },
  
  userBubbleWrapper: { 
    flexDirection: 'row', 
    justifyContent: 'flex-end',
    marginBottom: 8 
  },
  userBubble: { 
    backgroundColor: '#1B365D', 
    borderTopLeftRadius: 12,
    borderTopRightRadius: 12,
    borderBottomRightRadius: 4,
    borderBottomLeftRadius: 12,
    paddingHorizontal: 14, 
    paddingVertical: 10, 
    maxWidth: '85%' 
  },
  userMessageText: { color: '#F4F4F5', fontSize: 14, lineHeight: 20 },
  
  aiBubbleWrapper: { 
    flexDirection: 'row', 
    justifyContent: 'flex-start',
    marginBottom: 8 
  },
  aiBubble: { 
    backgroundColor: '#27272A',
    borderColor: '#3F3F46',
    borderWidth: 1,
    borderTopLeftRadius: 4,
    borderTopRightRadius: 12,
    borderBottomRightRadius: 12,
    borderBottomLeftRadius: 12,
    padding: 14,
    width: '92%'
  },
  aiTitle: {
    color: '#D4D4D8',
    fontWeight: 'bold',
    fontSize: 11,
    marginBottom: 5,
  },
  aiMessageText: { color: '#FAFAFA', fontSize: 14, lineHeight: 20 },
  
  inputArea: { 
    flexDirection: 'row', 
    padding: 12, 
    backgroundColor: '#27272A', 
    alignItems: 'center',
    borderTopWidth: 1,
    borderTopColor: '#3F3F46'
  },
  input: { 
    flex: 1, 
    backgroundColor: '#18181B', 
    color: '#FAFAFA', 
    paddingHorizontal: 16, 
    paddingVertical: 10, 
    borderRadius: 20, 
    marginRight: 8,
    borderWidth: 1,
    borderColor: '#3F3F46'
  },
  sendButton: { 
    backgroundColor: '#FAFAFA', 
    paddingHorizontal: 16, 
    paddingVertical: 10, 
    borderRadius: 20, 
    justifyContent: 'center', 
    alignItems: 'center' 
  },
  sendButtonText: { color: '#09090B', fontWeight: 'bold' },
});
