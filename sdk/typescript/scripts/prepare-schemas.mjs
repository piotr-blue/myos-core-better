/** Generator-only spelling fixes for OpenAPI 3.1; never edit the served contract. */
export function prepareSchemas(source) {
  const prepared = structuredClone(source);
  // Disjoint pagination modes must remain a union, not an intersection of fields.
  const timelinePage = prepared.components?.schemas?.TimelineEntriesPage;
  if (timelinePage?.anyOf) {
    timelinePage.oneOf = timelinePage.anyOf;
    delete timelinePage.anyOf;
  }


  function schema(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return;
    for (const key of ['properties', '$defs', 'patternProperties']) {
      for (const child of Object.values(value[key] ?? {})) schema(child);
    }
    for (const key of ['items', 'additionalProperties', 'not', 'if', 'then', 'else', 'contains', 'propertyNames']) {
      schema(value[key]);
    }
    for (const key of ['allOf', 'oneOf', 'anyOf', 'prefixItems']) {
      for (const child of value[key] ?? []) schema(child);
    }
    // The generator's dateLibrary=string loses null on nullable date-time fields.
    // Keep dates as their original wire strings; date validation remains server-side.
    if (value.format === 'date' || value.format === 'date-time') delete value.format;
    if (Object.hasOwn(value, 'const')) {
      const constant = value.const;
      if (!['string', 'boolean', 'number'].includes(typeof constant)) {
        throw new Error('Unsupported const in SDK schema preparation; review the new contract');
      }
      value.enum = [constant];
      value.type ??= typeof constant;
      delete value.const;
    }
    if (Array.isArray(value.type) && value.type.filter(type => type !== 'null').length > 1) {
      if (value.oneOf) throw new Error('Review combined type union and oneOf before generating');
      value.oneOf = value.type.map(type => ({ type }));
      delete value.type;
    }
    // typescript-fetch loses shared fields and required-only branches of object
    // anyOf. Materialize each branch as a full object for a TypeScript union.
    // Runtime validation is disabled, so overlapping branches remain accepted.
    if (value.type === 'object' && value.properties && value.anyOf) {
      if (value.oneOf || value.allOf) throw new Error('Review combined object compositions before generating');
      const { anyOf, description, ...shared } = value;
      const branches = anyOf.map(branch => {
        if (Object.keys(branch).some(key => !['required', 'properties'].includes(key))) {
          throw new Error('Review object anyOf branch constraints before generating');
        }
        const properties = structuredClone(shared.properties);
        for (const [name, refinement] of Object.entries(branch.properties ?? {})) {
          const original = properties[name];
          if (!original) throw new Error(`Review new anyOf property: ${name}`);
          for (const [key, constraint] of Object.entries(refinement)) {
            if (key === 'enum' && original.enum) {
              original.enum = original.enum.filter(item => constraint.includes(item));
            } else if (!Object.hasOwn(original, key) || JSON.stringify(original[key]) === JSON.stringify(constraint)) {
              original[key] = constraint;
            } else {
              throw new Error(`Review overlapping anyOf constraint: ${name}.${key}`);
            }
          }
        }
        return { ...structuredClone(shared), properties,
          required: [...new Set([...(shared.required ?? []), ...(branch.required ?? [])])] };
      });
      for (const key of Object.keys(value)) delete value[key];
      if (description !== undefined) value.description = description;
      value.oneOf = branches;
    }
    // Single-type nullable arrays MUST stay in 3.1 form. nullable:true loses null.
  }

  for (const value of Object.values(prepared.components?.schemas ?? {})) schema(value);
  function visit(value) {
    if (Array.isArray(value)) { value.forEach(visit); return; }
    if (!value || typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value)) {
      if (key === 'schema') schema(child);
      else if (!['example', 'examples', 'default', 'const', 'enum'].includes(key)) visit(child);
    }
  }
  visit(prepared.paths);
  for (const [key, value] of Object.entries(prepared.components ?? {})) {
    if (key !== 'schemas' && key !== 'examples') visit(value);
  }
  return prepared;
}
