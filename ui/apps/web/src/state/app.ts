import type { createActions } from "./actions";
import type { createCategories } from "./categories";
import type { createEntries } from "./entries";
import type { createSettings } from "./settings";
import type { createTasks } from "./tasks";
import type { createToasts } from "./toasts";

/**
 * Every module of the app, filled in order by `createApp`. A factory reaches
 * another module only inside a function body, never while it is being built.
 */
export interface App {
    toasts: ReturnType<typeof createToasts>;
    settings: ReturnType<typeof createSettings>;
    categories: ReturnType<typeof createCategories>;
    tasks: ReturnType<typeof createTasks>;
    entries: ReturnType<typeof createEntries>;
    actions: ReturnType<typeof createActions>;
}
