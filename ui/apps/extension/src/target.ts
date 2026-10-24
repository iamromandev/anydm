import type { Target } from "./flow";

/** What a click on the context menu says about where it happened (the part of `OnClickData` this uses). */
export interface MenuClick {
    linkUrl?: string;
    srcUrl?: string;
    pageUrl?: string;
}

/** The link the menu was used on: the link itself, else the source of the image, video or audio. */
export function targetOf(
    click: MenuClick,
    tab: { id?: number } | undefined,
): Target | undefined {
    const url = click.linkUrl ?? click.srcUrl;
    if (!url) return undefined;
    return { url, pageUrl: click.pageUrl, tabId: tab?.id };
}

/** The page that shows the flow for [target]; everything is in the address, so nothing is kept anywhere. */
export function popupPath(target: Target): string {
    const query = new URLSearchParams({ url: target.url });
    if (target.pageUrl) query.set("page", target.pageUrl);
    if (target.tabId !== undefined) query.set("tab", String(target.tabId));
    return `popup.html?${query}`;
}

/** Reads the address [popupPath] made (its `location.search`); undefined when it holds no link. */
export function parseTarget(search: string): Target | undefined {
    const query = new URLSearchParams(search);
    const url = query.get("url");
    if (!url) return undefined;
    const target: Target = { url };
    const page = query.get("page");
    if (page) target.pageUrl = page;
    const tab = query.get("tab");
    if (tab !== null && /^\d+$/.test(tab)) target.tabId = Number(tab);
    return target;
}
