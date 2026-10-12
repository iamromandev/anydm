import { createActions } from "./actions";
import { createAdd } from "./add";
import { createCategories } from "./categories";
import { createEntries } from "./entries";
import { createPlayer } from "./player";
import { createSettings } from "./settings";
import { createStream, type StreamOptions } from "./stream";
import type { Store } from "./store";
import { createTasks } from "./tasks";
import { createToasts } from "./toasts";

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
    player: ReturnType<typeof createPlayer>;
    add: ReturnType<typeof createAdd>;
    stream: ReturnType<typeof createStream>;
}

export type AppOptions = StreamOptions;

/**
 * Build every module. Each factory may use another module only inside a
 * function body, never while it is built, so the order below is free of cycles.
 */
export function createApp(store: Store, options: AppOptions = {}): App {
    const app = {} as App;
    app.toasts = createToasts(store);
    app.settings = createSettings(store, app);
    app.categories = createCategories(store, app);
    app.tasks = createTasks(store, app);
    app.entries = createEntries(store, app);
    app.actions = createActions(store, app);
    app.player = createPlayer(store, app);
    app.add = createAdd(store, app);
    app.stream = createStream(store, app, options);
    return app;
}
