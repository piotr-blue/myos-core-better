import { api, allItems } from "./api";
import { contentPath } from "./content-paths";

// Presentation is fetched by the selected immutable reference, so a newer
// publication cannot silently replace the epoch shown in the console.
export async function readPosition(position: any) {
  if (!position?.documentRef) return position;
  const document = await api(contentPath(position.documentRef.blueId));
  return { ...position, document };
}

export async function readDocument(id: string, metadata?: any) {
  const document = metadata ?? await api(`documents/${encodeURIComponent(id)}`);
  const [source, lastStable, channels] = await Promise.all([
    document.documentId ? api(contentPath(document.documentId)) : undefined,
    readPosition(document.lastStable),
    allItems(`documents/${encodeURIComponent(id)}/timelines`),
  ]);
  return { ...document, source, lastStable, channels };
}

export async function readTimelineEntries(id: string, pageToken = "") {
  return api(`timelines/${encodeURIComponent(id)}/entries?limit=25${pageToken ? `&pageToken=${encodeURIComponent(pageToken)}` : ""}`);
}
