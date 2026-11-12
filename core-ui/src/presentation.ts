// Only erase wrappers whose complete meaning is preserved by the scalar.
const scalarTypes: Record<string, string> = {
  GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC: "string",
  E2LM6qgzWG9ttagq2xTmiZkgYEAgkYedFCmU9v7NnVEq: "number",
  AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2: "boolean",
  "9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ": "number",
};
export function minimize(
  value: any,
  typeNames: Record<string, string> = {},
): any {
  if (Array.isArray(value)) return value.map((v) => minimize(v, typeNames));
  if (!value || typeof value !== "object") return value;
  const keys = Object.keys(value);
  if (
    keys.length === 2 &&
    keys.includes("type") &&
    keys.includes("value") &&
    Object.keys(value.type || {}).length === 1 &&
    (scalarTypes[value.type?.blueId] === typeof value.value || (scalarTypes[value.type?.blueId] === "number" && typeof value.value === "bigint"))
  )
    return value.value;
  // In particular, leave blueId references and unknown/custom type wrappers intact.
  return Object.fromEntries(
    Object.entries(value)
      .sort(([a], [b]) => fieldOrder(a) - fieldOrder(b))
      .map(([k, v]: [string, any]) => [
        k,
        ["type", "itemType", "keyType"].includes(k) &&
        v &&
        (typeof v === "string"
          ? typeNames[v.replace(/\s*\/\s*/g, "/")]
          : Object.keys(v).length === 1 && typeNames[v.blueId])
          ? typeNames[
              typeof v === "string" ? v.replace(/\s*\/\s*/g, "/") : v.blueId
            ]
          : minimize(v, typeNames),
      ]),
  );
}

function fieldOrder(key: string) {
  return (
    (
      { name: -3, description: -2, specification: -1, contracts: 1 } as Record<
        string,
        number
      >
    )[key] ?? 0
  );
}
