/**
 * Lossy presentation of an already obtained Blue value. Scalar/list envelopes
 * become their values; structured nodes and exact references retain their type.
 * This does not resolve types, validate content, or establish authority. Never
 * use the projection to rebuild signed/hashed content or as an operation source.
 * Parse large numeric tokens losslessly before calling this helper when needed.
 */
export function projectBlueData(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(projectBlueData);
  if (value === null || typeof value !== 'object') return value;
  const node = value as Record<string, unknown>;
  const keys = Object.keys(node);
  const envelope = keys.every(key => ['name', 'description', 'type', 'schema', 'value', 'items'].includes(key));
  const listEnvelope = keys.every(key => ['name', 'description', 'type', 'schema', 'itemType', 'items'].includes(key));
  if (envelope && Object.hasOwn(node, 'value') && !Object.hasOwn(node, 'items') &&
      (node.value === null || ['string', 'number', 'bigint', 'boolean'].includes(typeof node.value))) return node.value;
  if (listEnvelope && Array.isArray(node.items) && !Object.hasOwn(node, 'value')) return node.items.map(projectBlueData);
  return Object.fromEntries(Object.entries(node).map(([key, item]) => [key, projectBlueData(item)]));
}
