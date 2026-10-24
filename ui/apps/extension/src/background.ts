import { ext } from "./api";
import { popupPath, targetOf } from "./target";

const MENU = "anydm-download";

ext.runtime.onInstalled.addListener(() => {
    // The menu outlives the worker; an update would otherwise make it twice.
    void ext.contextMenus.removeAll().then(() =>
        ext.contextMenus.create({
            id: MENU,
            title: "Download with anydm",
            contexts: [
                "link",
                "image",
                "video",
                "audio",
            ],
        }),
    );
});

// Nothing is read or kept here: the page that opens does the work, from what is in its address.
ext.contextMenus.onClicked.addListener((info, tab) => {
    if (info.menuItemId !== MENU) return;
    const target = targetOf(info, tab);
    if (!target) return;
    void ext.windows.create({
        url: ext.runtime.getURL(popupPath(target)),
        type: "popup",
        width: 420,
        height: 340,
    });
});
