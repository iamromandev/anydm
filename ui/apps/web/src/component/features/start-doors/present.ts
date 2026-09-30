/**
 * The ways to start, shown when there is nothing yet, decided without the DOM so it is tested with plain values.
 */

export type DoorId = "paste" | "drop" | "browse";

export type Door = {
    id: DoorId;
    title: string;
    hint: string;
    /** A button that does something; the drop door only says where a file can go. */
    action: boolean;
};

const PASTE: Door = {
    id: "paste",
    title: "Paste a link",
    hint: "A video page, a file, or a magnet.",
    action: true,
};

const DROP: Door = {
    id: "drop",
    title: "Drop a .torrent file",
    hint: "Anywhere on this page.",
    action: false,
};

const BROWSE: Door = {
    id: "browse",
    title: "Browse latest torrents",
    hint: "The newest releases from your sources.",
    action: true,
};

/** Browse latest is a torrent list, so it is left out when there are no torrent sources. */
export function doorsFor(torrentSearch: boolean): Door[] {
    return torrentSearch
        ? [
              PASTE,
              DROP,
              BROWSE,
          ]
        : [
              PASTE,
              DROP,
          ];
}

/** Only for an empty list nobody has searched: a search that found nothing has its own message. */
export function showDoors(taskCount: number, searchQuery: string): boolean {
    return taskCount === 0 && searchQuery.trim() === "";
}
