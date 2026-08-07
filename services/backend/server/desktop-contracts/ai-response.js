import { z } from 'zod';
import { EditOperationBatchSchema } from './operations.js';

const reply = z.string().min(1);
const textList = z.array(z.string().min(1));

export const ClarificationQuestionSchema = z.object({
  id: z.string().min(1),
  prompt: z.string().min(1),
  options: z.array(z.object({
    id: z.string().min(1),
    label: z.string().min(1),
    description: z.string().min(1),
  }).strict()).min(2).max(4),
  recommendedOptionId: z.string().min(1).nullable(),
}).strict();

export const AiResponseSchema = z.discriminatedUnion('mode', [
  z.object({
    mode: z.literal('clarification'),
    reply,
    questions: z.array(ClarificationQuestionSchema).min(1).max(3),
  }).strict(),
  z.object({
    mode: z.literal('proposal'),
    reply,
    operations: EditOperationBatchSchema,
    evidence: textList,
    assumptions: textList,
    warnings: textList,
    requiredCleanup: textList,
    optionalStyle: textList,
    estimatedDuration: z.number().finite().nonnegative(),
  }).strict(),
  z.object({
    mode: z.literal('message'),
    reply,
  }).strict(),
  z.object({
    mode: z.literal('command'),
    reply,
    command: z.enum(['export']),
  }).strict(),
]);

export function parseAiResponse(input) {
  if (input && typeof input === 'object') {
    input = structuredClone(input);
    for (const [key, value] of Object.entries(input)) {
      if (value === null) delete input[key];
    }

    // Structured Outputs uses one flattened envelope whose fields are all
    // required (and nullable). Some models populate unused fields instead of
    // returning null, so remove only known fields belonging to other modes.
    const fieldsByMode = {
      clarification: new Set(['mode', 'reply', 'questions']),
      proposal: new Set(['mode', 'reply', 'operations', 'evidence', 'assumptions', 'warnings', 'requiredCleanup', 'optionalStyle', 'estimatedDuration']),
      message: new Set(['mode', 'reply']),
      command: new Set(['mode', 'reply', 'command']),
    };
    const envelopeFields = new Set(Object.values(fieldsByMode).flatMap((fields) => [...fields]));
    const allowedFields = fieldsByMode[input.mode];
    if (allowedFields) {
      for (const key of Object.keys(input)) {
        if (envelopeFields.has(key) && !allowedFields.has(key)) delete input[key];
      }
    }
    if (input.mode === 'proposal') {
      for (const key of ['evidence', 'assumptions', 'warnings', 'requiredCleanup', 'optionalStyle']) {
        if (input[key] == null) input[key] = [];
      }
    }
  }
  if (input && typeof input === 'object' && input.mode === 'proposal' && Array.isArray(input.operations)) {
    for (const operation of input.operations) {
      if (operation?.type === 'update_item' && operation.changes && typeof operation.changes === 'object') {
        for (const [key, value] of Object.entries(operation.changes)) {
          if (value === null) delete operation.changes[key];
        }
      }

    }
  }
  return AiResponseSchema.parse(input);
}

export function aiResponseJsonSchema() {
  const schema = z.toJSONSchema(AiResponseSchema, {
    target: 'draft-7',
    unrepresentable: 'throw',
  });

  // The provider requires the root schema itself to be an object. Flatten the
  // response variants into one object envelope; fields unused by a mode are
  // nullable and are removed before Zod validation.
  const branches = schema.oneOf || schema.anyOf;
  if (Array.isArray(branches)) {
    const keys = [...new Set(branches.flatMap((branch) => Object.keys(branch.properties || {})))];
    const properties = {};
    for (const key of keys) {
      const variants = branches.map((branch) => branch.properties?.[key] || { type: 'null' });
      properties[key] = { anyOf: variants };
    }
    for (const key of Object.keys(schema)) delete schema[key];
    Object.assign(schema, { type: 'object', properties, required: keys, additionalProperties: false });
  }

  // Zod emits discriminated unions as `oneOf`. OpenAI structured outputs
  // rejects `oneOf` but supports `anyOf`; the literal `mode` property keeps
  // these branches mutually exclusive.
  const normalizeUnions = (value) => {
    if (!value || typeof value !== 'object') return;
    if (Array.isArray(value.oneOf)) {
      value.anyOf = value.oneOf;
      delete value.oneOf;
    }
    if (value.type === 'object' && value.properties) {
      const previouslyRequired = new Set(value.required || []);
      for (const [key, propertySchema] of Object.entries(value.properties)) {
        if (!previouslyRequired.has(key)) {
          value.properties[key] = { anyOf: [propertySchema, { type: 'null' }] };
        }
      }
      value.required = Object.keys(value.properties);
    }
    for (const child of Object.values(value)) {
      if (Array.isArray(child)) child.forEach(normalizeUnions);
      else normalizeUnions(child);
    }
  };
  normalizeUnions(schema);
  return schema;
}
