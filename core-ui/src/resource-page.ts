import type { ReadState } from "./transport";

export type ResourcePage = {
  items: any[];
  readState?: ReadState;
  total: number;
  nextPageToken?: string | null;
};
export type ResourceState = {
  rows: any[];
  readState?: ReadState;
  total: number;
  next: string | null;
  ready: boolean;
  loading: boolean;
  error: string;
};
export const emptyResourceState = (): ResourceState => ({
  rows: [],
  total: 0,
  next: null,
  ready: false,
  loading: false,
  error: "",
});

/** One request per user-visible page. Never walk the whole account before rendering. */
export class ResourcePager {
  state = emptyResourceState();
  private controller?: AbortController;
  private pending?: Promise<void>;
  private dirty = false;
  private stopped = false;
  constructor(
    private path: string,
    private read: (path: string, signal: AbortSignal) => Promise<ResourcePage>,
    private notify: (state: ResourceState) => void,
  ) {}
  first = () => {
    if (this.pending) {
      this.dirty = true;
      return this.pending;
    }
    return this.request(null);
  };
  more = () =>
    this.pending ||
    (this.state.next ? this.request(this.state.next) : Promise.resolve());
  dispose() {
    this.stopped = true;
    this.controller?.abort();
  }
  private publish(change: Partial<ResourceState>) {
    if (this.stopped) return;
    this.state = { ...this.state, ...change };
    this.notify(this.state);
  }
  private request(token: string | null): Promise<void> {
    if (this.stopped) return Promise.resolve();
    this.controller = new AbortController();
    this.publish({ loading: true, error: "" });
    const path =
      this.path + (token ? `&pageToken=${encodeURIComponent(token)}` : "");
    this.pending = this.read(path, this.controller.signal)
      .then((page) => {
        if (this.stopped) return;
        const rows = token ? [...this.state.rows, ...page.items] : page.items;
        const unique = new Map(
          rows.map((row) => [row.sessionId || row.timelineId || row.id, row]),
        );
        this.publish({
          rows: [...unique.values()],
          total: page.total,
          next: page.nextPageToken || null,
          ready: true,
          readState: page.readState,
        });
      })
      .catch((error) => {
        if (!this.stopped) this.publish({ error: error.message, ready: true });
      })
      .finally(() => {
        this.pending = undefined;
        this.publish({ loading: false });
        if (this.dirty && !this.stopped) {
          this.dirty = false;
          void this.first();
        }
      });
    return this.pending;
  }
}
