import React, { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, ActivityIndicator, FlatList, ScrollView } from 'react-native';
import Animated, { FadeInDown, FadeOut, Layout } from 'react-native-reanimated';
import EventSource from 'react-native-sse';
import { useProjectStore } from '../store/projectStore';
import { BACKEND_URL } from '../utils/config';
import { AssetPicker } from './AssetPicker';

interface Message {
  id: string;
  role: 'user' | 'ai';
  content: string;
}

export const Chat = () => {
  const presentSchema = useProjectStore(state => state.present);
  const processToolCall = useProjectStore(state => state.processToolCall);
  
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);

  const hasMedia = presentSchema.timeline.length > 0;

  const sendMessage = (text = inputText) => {
    if (!text.trim()) return;

    const userMsg: Message = { id: Date.now().toString(), role: 'user', content: text };
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
            <Text style={styles.aiTitle}>Trimm AI</Text>
            <Text style={styles.aiMessageText}>{item.content}</Text>
          </View>
        </Animated.View>
      );
    }
  };

  const suggestions = hasMedia 
    ? ["Add cinematic text", "Add a slow zoom", "Trim the first 2 seconds"]
    : ["Make it cinematic", "Add a black background", "Create a title sequence"];

  return (
    <View style={styles.container}>
      {messages.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyTitle}>
            {hasMedia ? "What should we change?" : "Ready to edit?"}
          </Text>
          <Text style={styles.emptySubtitle}>
            {hasMedia ? "Describe your edits below or use a suggestion." : "Import media to get started, or describe what you want to edit."}
          </Text>
        </View>
      ) : (
        <FlatList
          data={messages}
          keyExtractor={item => item.id}
          renderItem={renderItem}
          contentContainerStyle={styles.messageList}
          inverted={false}
        />
      )}

      {messages.length === 0 && (
        <View>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.suggestionsScroll}>
            {suggestions.map((suggestion, idx) => (
              <TouchableOpacity key={idx} style={styles.suggestionBadge} onPress={() => sendMessage(suggestion)}>
                <Text style={styles.suggestionText}>{suggestion}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
        </View>
      )}

      <View style={styles.inputArea}>
        <TextInput
          style={styles.input}
          placeholder="Ask AI to edit..."
          placeholderTextColor="#A1A1AA"
          value={inputText}
          onChangeText={setInputText}
          onSubmitEditing={() => sendMessage(inputText)}
        />
        <TouchableOpacity style={styles.sendButton} onPress={() => sendMessage(inputText)} disabled={isProcessing}>
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
    marginBottom: 12 
  },
  userBubble: { 
    backgroundColor: '#FAFAFA', 
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    borderBottomLeftRadius: 16,
    borderBottomRightRadius: 4,
    paddingHorizontal: 16, 
    paddingVertical: 12, 
    maxWidth: '85%' 
  },
  userMessageText: { color: '#09090B', fontSize: 15, lineHeight: 22 },
  
  aiBubbleWrapper: { 
    flexDirection: 'row', 
    justifyContent: 'flex-start',
    marginBottom: 12 
  },
  aiBubble: { 
    backgroundColor: '#18181B',
    borderColor: '#27272A',
    borderWidth: 1,
    borderTopLeftRadius: 4,
    borderTopRightRadius: 16,
    borderBottomLeftRadius: 16,
    borderBottomRightRadius: 16,
    padding: 16,
    width: '92%'
  },
  aiTitle: {
    color: '#A1A1AA',
    fontWeight: '600',
    fontSize: 12,
    marginBottom: 6,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  aiMessageText: { color: '#FAFAFA', fontSize: 15, lineHeight: 22 },
  
  emptyState: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  emptyTitle: {
    color: '#FAFAFA',
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 8,
    textAlign: 'center',
  },
  emptySubtitle: {
    color: '#A1A1AA',
    fontSize: 15,
    textAlign: 'center',
    marginBottom: 32,
    lineHeight: 22,
  },
  suggestionsScroll: {
    paddingHorizontal: 12,
    paddingBottom: 12,
  },
  suggestionBadge: {
    backgroundColor: '#18181B',
    borderWidth: 1,
    borderColor: '#27272A',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 20,
    marginHorizontal: 4,
  },
  suggestionText: {
    color: '#E4E4E7',
    fontSize: 14,
    fontWeight: '500',
  },

  inputArea: { 
    flexDirection: 'row', 
    paddingHorizontal: 12,
    paddingBottom: 0,
    backgroundColor: 'transparent', 
    alignItems: 'center',
  },
  input: { 
    flex: 1, 
    backgroundColor: '#18181B', 
    color: '#FAFAFA', 
    paddingHorizontal: 16, 
    paddingVertical: 12, 
    borderRadius: 24, 
    marginRight: 10,
    borderWidth: 1,
    borderColor: '#27272A',
    fontSize: 15,
  },
  sendButton: { 
    backgroundColor: '#FAFAFA', 
    paddingHorizontal: 20, 
    paddingVertical: 12, 
    borderRadius: 24, 
    justifyContent: 'center', 
    alignItems: 'center' 
  },
  sendButtonText: { color: '#09090B', fontWeight: 'bold', fontSize: 15 },
});
