import { create } from 'zustand';
import { ProjectSchema } from '../types/schema';
import { AgentToolCall } from '../types/contracts';
import { useAuthStore } from './authStore';
import { BACKEND_URL } from '../utils/config';

interface HistoryState {
  past: ProjectSchema[];
  present: ProjectSchema;
  future: ProjectSchema[];
}

interface ProjectState extends HistoryState {
  processToolCall: (call: AgentToolCall) => void;
  undo: () => void;
  redo: () => void;
  resetProject: () => void;
}

const generateId = () => Math.random().toString(36).substring(2, 9);

const initialSchema: ProjectSchema = {
  settings: {
    aspectRatio: '16:9',
    fps: 30,
    backgroundColor: '#000000',
  },
  timeline: [],
};

export const useProjectStore = create<ProjectState>((set) => ({
  past: [],
  present: initialSchema,
  future: [],

  processToolCall: (call: AgentToolCall) => set((state) => {
    // Clone current schema for mutation
    const newSchema = JSON.parse(JSON.stringify(state.present)) as ProjectSchema;
    let newTimeline = newSchema.timeline;

    switch (call.tool) {
      case 'update_settings':
        if (call.aspectRatio) newSchema.settings.aspectRatio = call.aspectRatio;
        if (call.fps) newSchema.settings.fps = call.fps;
        if (call.backgroundColor) newSchema.settings.backgroundColor = call.backgroundColor;
        break;

      case 'add_clip':
        newTimeline.push({
          type: call.type,
          id: `clip_${generateId()}`,
          src: call.source,
          start: call.start,
          end: call.end,
          volume: call.type === 'video' ? call.volume : undefined,
          transform: call.transform,
        });
        break;

      case 'remove_clip':
        newTimeline = newTimeline.filter(node => node.id !== call.targetId);
        break;

      case 'trim_clip':
        newTimeline = newTimeline.map(node => {
          if (node.id === call.targetId && (node.type === 'video' || node.type === 'audio')) {
            const oldStart = Number(node.start);
            const newStart = call.start !== undefined ? Number(call.start) : oldStart;
            const diff = newStart - oldStart;
            return {
              ...node,
              start: newStart,
              end: call.end !== undefined ? Number(call.end) : Number(node.end),
              sourceStart: (Number(node.sourceStart) || 0) + diff,
            };
          }
          return node;
        });
        break;

      case 'split_clip':
        // A simple split: duplicate the node and adjust start/end
        const targetIndex = newTimeline.findIndex(node => node.id === call.targetId);
        if (targetIndex !== -1) {
          const target = newTimeline[targetIndex];
          if (target.start < call.time && target.end > call.time) {
            const part1 = { ...target, id: `clip_${generateId()}`, end: call.time };
            const part2 = { ...target, id: `clip_${generateId()}`, start: call.time };
            newTimeline.splice(targetIndex, 1, part1, part2);
          }
        }
        break;

      case 'add_text':
        newTimeline.push({
          type: 'text',
          id: `text_${generateId()}`,
          content: call.content,
          start: call.start,
          end: call.end,
          fontSize: call.fontSize || 32,
          fontFamily: call.fontFamily || 'System',
          color: call.color || '#FFFFFF',
          backgroundColor: call.backgroundColor,
          transform: call.transform,
        });
        break;

      case 'update_text':
        newTimeline = newTimeline.map(node => {
          if (node.id === call.targetId && node.type === 'text') {
            return {
              ...node,
              content: call.content !== undefined ? call.content : node.content,
              fontSize: call.fontSize !== undefined ? call.fontSize : node.fontSize,
              fontFamily: call.fontFamily !== undefined ? call.fontFamily : node.fontFamily,
              color: call.color !== undefined ? call.color : node.color,
              backgroundColor: call.backgroundColor !== undefined ? call.backgroundColor : node.backgroundColor,
              transform: call.transform !== undefined ? call.transform : node.transform,
            };
          }
          return node;
        });
        break;

      case 'remove_text':
        newTimeline = newTimeline.filter(node => node.id !== call.targetId);
        break;

      case 'add_animation':
        newTimeline = newTimeline.map(node => {
          if (node.id === call.targetId) {
            return { ...node, animation: call.animation };
          }
          return node;
        });
        break;

      case 'remove_animation':
        newTimeline = newTimeline.map(node => {
          if (node.id === call.targetId) {
            const copy = { ...node };
            delete (copy as any).animation;
            return copy;
          }
          return node;
        });
        break;

      case 'set_blend_mode':
        newTimeline = newTimeline.map(node => {
          if (node.id === call.targetId) {
            return {
              ...node,
              blendMode: call.blendMode,
              opacity: call.opacity !== undefined ? call.opacity : node.opacity,
            };
          }
          return node;
        });
        break;

      case 'add_audio':
        newTimeline.push({
          type: 'audio',
          id: `audio_${generateId()}`,
          src: call.source,
          start: call.start,
          end: call.end,
          volume: call.volume,
        });
        break;

      case 'adjust_volume':
        newTimeline = newTimeline.map(node => {
          if (node.id === call.targetId && (node.type === 'video' || node.type === 'audio')) {
            return { ...node, volume: call.volume };
          }
          return node;
        });
        break;
    }

    newSchema.timeline = newTimeline;

    if (!newSchema.id) {
      // Use standard UUID v4 for the project ID so it can be inserted into Supabase UUID columns
      newSchema.id = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
      });
    }

    // Fire and forget sync to backend
    const session = useAuthStore.getState().session;
    if (session?.access_token) {
      fetch(`${BACKEND_URL}/v1/projects/sync`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${session.access_token}`
        },
        body: JSON.stringify({
          ...newSchema,
          title: 'New Edit'
        })
      }).catch(err => console.error('Failed to sync project', err));
    }

    // Update state with history
    return {
      past: [...state.past, state.present],
      present: newSchema,
      future: [], // clear future on new action
    };
  }),

  undo: () => set((state) => {
    if (state.past.length === 0) return state;
    const previous = state.past[state.past.length - 1];
    const newPast = state.past.slice(0, -1);
    return {
      past: newPast,
      present: previous,
      future: [state.present, ...state.future],
    };
  }),

  redo: () => set((state) => {
    if (state.future.length === 0) return state;
    const next = state.future[0];
    const newFuture = state.future.slice(1);
    return {
      past: [...state.past, state.present],
      present: next,
      future: newFuture,
    };
  }),

  resetProject: () => set({
    past: [],
    present: initialSchema,
    future: [],
  }),
}));
