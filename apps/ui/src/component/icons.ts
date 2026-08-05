import type { JSX } from "@qwik.dev/core";

// @qwikest/icons is built for @builder.io/qwik (Qwik 1) and its JSXNode
// type is incompatible with @qwik.dev/core (Qwik 2). This wrapper
// re-exports the icons with the correct JSX type.
type IconComponent = (props: Record<string, unknown>) => JSX.Element;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function icon(mod: any, name: string): IconComponent {
    return mod[name] as IconComponent;
}

// Lucide icons
import {
    LuDownload as _LuDownload,
    LuPause as _LuPause,
    LuX as _LuX,
    LuTrash as _LuTrash,
    LuRefreshCw as _LuRefreshCw,
    LuFolderOpen as _LuFolderOpen,
    LuHardDrive as _LuHardDrive,
    LuArrowDownToLine as _LuArrowDownToLine,
    LuCheckCircle as _LuCheckCircle,
    LuXCircle as _LuXCircle,
    LuAlertTriangle as _LuAlertTriangle,
    LuGlobe as _LuGlobe,
    LuMusic2 as _LuMusic2,
    LuFilm as _LuFilm,
    LuMonitorPlay as _LuMonitorPlay,
    LuList as _LuList,
    LuLoader2 as _LuLoader2,
    LuLink as _LuLink,
    LuClock as _LuClock,
    LuChevronDown as _LuChevronDown,
    LuCopy as _LuCopy,
    LuCircle as _LuCircle,
} from "@qwikest/icons/lucide";

export const LuDownload = icon(_LuDownload, "LuDownload");
export const LuPause = icon(_LuPause, "LuPause");
export const LuX = icon(_LuX, "LuX");
export const LuTrash = icon(_LuTrash, "LuTrash");
export const LuRefreshCw = icon(_LuRefreshCw, "LuRefreshCw");
export const LuFolderOpen = icon(_LuFolderOpen, "LuFolderOpen");
export const LuHardDrive = icon(_LuHardDrive, "LuHardDrive");
export const LuArrowDownToLine = icon(_LuArrowDownToLine, "LuArrowDownToLine");
export const LuCheckCircle = icon(_LuCheckCircle, "LuCheckCircle");
export const LuXCircle = icon(_LuXCircle, "LuXCircle");
export const LuAlertTriangle = icon(_LuAlertTriangle, "LuAlertTriangle");
export const LuGlobe = icon(_LuGlobe, "LuGlobe");
export const LuMusic2 = icon(_LuMusic2, "LuMusic2");
export const LuFilm = icon(_LuFilm, "LuFilm");
export const LuMonitorPlay = icon(_LuMonitorPlay, "LuMonitorPlay");
export const LuList = icon(_LuList, "LuList");
export const LuLoader2 = icon(_LuLoader2, "LuLoader2");
export const LuLink = icon(_LuLink, "LuLink");
export const LuClock = icon(_LuClock, "LuClock");
export const LuChevronDown = icon(_LuChevronDown, "LuChevronDown");
export const LuCopy = icon(_LuCopy, "LuCopy");
export const LuCircle = icon(_LuCircle, "LuCircle");

// Simple Icons (brand logos)
import {
    SiYoutube as _SiYoutube,
    SiTiktok as _SiTiktok,
    SiInstagram as _SiInstagram,
    SiX as _SiX,
    SiVimeo as _SiVimeo,
    SiTwitch as _SiTwitch,
} from "@qwikest/icons/simpleicons";

export const SiYoutube = icon(_SiYoutube, "SiYoutube");
export const SiTiktok = icon(_SiTiktok, "SiTiktok");
export const SiInstagram = icon(_SiInstagram, "SiInstagram");
export const SiX = icon(_SiX, "SiX");
export const SiVimeo = icon(_SiVimeo, "SiVimeo");
export const SiTwitch = icon(_SiTwitch, "SiTwitch");
