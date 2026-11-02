import type { DownloadsApi, DownloadsEvents } from "./capture";
import type { CookieApi } from "./cookies";
import type { PairingStorage } from "./pairing";
import type { Runtime } from "./rpc";
import type { SettingsStorage } from "./settings";
import type { MenuClick } from "./target";

/** What a permission question or answer holds. */
export interface Access {
    permissions?: string[];
    origins?: string[];
}

/** The part of the browser's extension API this uses; Chrome and Firefox both answer with promises in Manifest V3. */
export interface ExtApi {
    contextMenus: {
        removeAll(): Promise<void>;
        create(properties: {
            id: string;
            title: string;
            contexts: string[];
        }): unknown;
        onClicked: {
            addListener(
                listener: (
                    info: MenuClick & { menuItemId: string | number },
                    tab?: { id?: number },
                ) => void,
            ): void;
        };
    };
    cookies: CookieApi;
    /** Absent while the optional `downloads` permission is not held. */
    downloads?: DownloadsApi & DownloadsEvents;
    storage: { local: SettingsStorage & PairingStorage };
    permissions: {
        contains(query: Access): Promise<boolean>;
        request(query: Access): Promise<boolean>;
        remove(query: Access): Promise<boolean>;
        onAdded: { addListener(listener: (added: Access) => void): void };
        onRemoved: { addListener(listener: (removed: Access) => void): void };
    };
    windows: {
        create(properties: {
            url: string;
            type: "popup";
            width: number;
            height: number;
        }): Promise<unknown>;
    };
    runtime: Runtime & {
        getURL(path: string): string;
        sendMessage(message: unknown): Promise<unknown>;
        onInstalled: { addListener(listener: () => void): void };
    };
}

const scope = globalThis as unknown as { browser?: ExtApi; chrome?: ExtApi };

/** The browser's API: Firefox's `browser`, else Chrome's `chrome`. */
export const ext: ExtApi = (scope.browser ?? scope.chrome)!;

/** Firefox has `browser`; Chrome has only `chrome`. Firefox is the one with cookie containers. */
export const isFirefox = scope.browser !== undefined;
