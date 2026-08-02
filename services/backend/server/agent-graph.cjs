'use strict';

const { Annotation, END, START, StateGraph } = require('@langchain/langgraph');

const GraphState = Annotation.Root({
  mode: Annotation(),
  principal: Annotation(),
  inventory: Annotation(),
  outcome: Annotation(),
});

class AgentGraph {
  constructor({ orchestrator }) {
    if (!orchestrator) throw new TypeError('AgentGraph requires an orchestrator.');
    this.graph = new StateGraph(GraphState)
      .addNode('bootstrap', async (state) => ({
        outcome: await orchestrator.bootstrap(state.principal, state.inventory),
      }))
      .addNode('reason', async (state) => ({
        outcome: await orchestrator.orchestrate(state.principal, state.inventory),
      }))
      .addConditionalEdges(START, (state) => state.mode === 'start' ? 'bootstrap' : 'reason', {
        bootstrap: 'bootstrap', reason: 'reason',
      })
      .addConditionalEdges('bootstrap', (state) => state.outcome ? 'done' : 'reason', {
        done: END, reason: 'reason',
      })
      .addEdge('reason', END)
      .compile();
  }

  async invoke({ mode, principal, inventory, threadId }) {
    const state = await this.graph.invoke({ mode, principal, inventory }, {
      configurable: { thread_id: threadId },
    });
    return state.outcome;
  }
}

module.exports = { AgentGraph };
