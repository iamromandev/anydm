import type { createCategories } from "./categories";
import type { createSettings } from "./settings";
import type { createToasts } from "./toasts";

/**
 * Every module of the app, filled in order by `createApp`. A factory reaches
 * another module only inside a function body, never while it is being built.
 */
export interface App {
    toasts: ReturnType<typeof createToasts>;
    settings: ReturnType<typeof createSettings>;
    categories: ReturnType<typeof createCategories>;
    // Replaced by `ReturnType<typeof createTasks>` when the tasks module lands.
    tasks: { loadPage(page: number): Promise<void>; sync(): Promise<void> };
}
