import test from 'node:test';
import assert from 'node:assert/strict';
import { getAgentTools, APPLY_SCHEMA_PATCH_TOOL } from '../schema/agent-decision-schema.js';

test('agent tools include apply_schema_patch with visible property for text nodes', () => {
  const tools = getAgentTools();
  const toolNames = tools.map(t => t.name);
  assert.ok(toolNames.includes('apply_schema_patch'), 'apply_schema_patch tool should exist');

  const patchTool = tools.find(t => t.name === 'apply_schema_patch');
  assert.ok(patchTool, 'apply_schema_patch tool must be present');
  
  const timelineItemProps = patchTool.parameters.properties.schema.properties.timeline.items.properties;
  assert.ok(timelineItemProps.visible, 'visible property must exist in timeline item schema');
  assert.equal(timelineItemProps.visible.type, 'boolean');
});

test('apply_schema_patch tool schema requires settings and timeline', () => {
  const schemaProps = APPLY_SCHEMA_PATCH_TOOL.parameters.properties.schema;
  assert.deepEqual(schemaProps.required, ['settings', 'timeline']);
  
  const settingsProps = schemaProps.properties.settings.properties;
  assert.ok(settingsProps.aspectRatio);
  assert.ok(settingsProps.backgroundColor);
  assert.ok(settingsProps.autoEnhance);
});

