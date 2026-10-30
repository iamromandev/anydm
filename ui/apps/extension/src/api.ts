import type { CookieApi } from "./cookies";
import type { MenuClick } from "./target";

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
    permissions: {
        contains(query: { origins: string[] }): Promise<boolean>;
        request(query: { origins: string[] }): Promise<boolean>;
    };
    windows: {
        create(properties: {
            url: string;
            type: "popup";
            width: number;
            height: number;
        }): Promise<unknown>;
    };
    runtime: {
        getURL(path: string): string;
        onInstalled: { addListener(listener: () => void): void };
    };
}

const scope = globalThis as unknown as { browser?: ExtApi; chrome?: ExtApi };

/** The browser's API: Firefox's `browser`, else Chrome's `chrome`. */
export const ext: ExtApi = (scope.browser ?? scope.chrome)!;
