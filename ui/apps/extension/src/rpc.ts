import type { AddOutcome, ConfirmOutcome } from "./bridge";
import { PORTS } from "./bridge";
import type { BridgeApi, Connected } from "./client";
import type { AddBody } from "./request";

// The app has one session per paired browser, and a new hello ends the last, so exactly one part of the extension, the
// background worker, says hello and signs. A page (the window of the context menu, the toolbar window) reaches the app by
// asking the worker with these messages. They go only between this extension's own parts.

const CHANNEL = "bridge-1";

type Call =
    | { anydm: typeof CHANNEL; op: "connect"; preferred?: number }
    | { anydm: typeof CHANNEL; op: "add"; port: number; body: AddBody }
    | { anydm: typeof CHANNEL; op: "confirm"; port: number; token: string };

/** The part of the browser's `runtime` the worker uses. */
export interface Runtime {
    id: string;
    onMessage: {
        addListener(
            listener: (
                message: unknown,
                sender: { id?: string },
                sendResponse: (reply: unknown) => void,
            ) => boolean | undefined,
        ): void;
    };
}

const isPort = (port: unknown): port is number =>
    typeof port === "number" && PORTS.includes(port);

const isObject = (value: unknown): value is Record<string, unknown> =>
    typeof value === "object" && value !== null && !Array.isArray(value);

// Only a call of the right shape gets as far as the bridge, whatever sent it.
function readCall(message: unknown): Call | null {
    if (!isObject(message) || message.anydm !== CHANNEL) return null;
    switch (message.op) {
        case "connect":
            return message.preferred === undefined ||
                typeof message.preferred === "number"
                ? {
                      anydm: CHANNEL,
                      op: "connect",
                      preferred: message.preferred,
                  }
                : null;
        case "add":
            return isPort(message.port) &&
                isObject(message.body) &&
                typeof message.body.url === "string"
                ? {
                      anydm: CHANNEL,
                      op: "add",
                      port: message.port,
                      body: message.body as unknown as AddBody,
                  }
                : null;
        case "confirm":
            return isPort(message.port) && typeof message.token === "string"
                ? {
                      anydm: CHANNEL,
                      op: "confirm",
                      port: message.port,
                      token: message.token,
                  }
                : null;
        default:
            return null;
    }
}

async function run(bridge: BridgeApi, call: Call): Promise<unknown> {
    switch (call.op) {
        case "connect":
            return bridge.connect(call.preferred);
        case "add":
            return bridge.add(call.port, call.body);
        case "confirm":
            return bridge.confirm(call.port, call.token);
    }
}

/** Makes the worker answer the pages: [bridge] is the session's owner. A message that is not for it is left alone. */
export function serveBridge(bridge: BridgeApi, runtime: Runtime) {
    runtime.onMessage.addListener((message, sender, sendResponse) => {
        // Not a message of ours, or not from this extension (another extension's arrive elsewhere, but be sure).
        if (!isObject(message) || message.anydm !== CHANNEL) return false;
        if (sender.id === undefined || sender.id !== runtime.id) return false;
        const call = readCall(message);
        if (call === null) {
            sendResponse({ ok: false });
            return true;
        }
        run(bridge, call).then(
            (value) => sendResponse({ ok: true, value }),
            () => sendResponse({ ok: false }),
        );
        // The answer comes later, through [sendResponse].
        return true;
    });
}

const valueOf = (reply: unknown): unknown =>
    isObject(reply) && reply.ok === true ? reply.value : undefined;

/** The bridge as a page has it: every call asks the worker through [send] (the browser's `runtime.sendMessage`). */
export function remoteBridge(
    send: (message: unknown) => Promise<unknown>,
): BridgeApi {
    const ask = async (call: Call): Promise<unknown> => {
        try {
            return valueOf(await send(call));
        } catch {
            return undefined;
        }
    };
    return {
        async connect(preferred) {
            const value = await ask({
                anydm: CHANNEL,
                op: "connect",
                preferred,
            });
            return isObject(value) && typeof value.kind === "string"
                ? (value as unknown as Connected)
                : { kind: "no-background" };
        },
        async add(port, body) {
            const value = await ask({ anydm: CHANNEL, op: "add", port, body });
            return isObject(value) && typeof value.kind === "string"
                ? (value as unknown as AddOutcome)
                : { kind: "unreachable" };
        },
        async confirm(port, token) {
            const value = await ask({
                anydm: CHANNEL,
                op: "confirm",
                port,
                token,
            });
            return isObject(value) && typeof value.kind === "string"
                ? (value as unknown as ConfirmOutcome)
                : { kind: "unreachable" };
        },
    };
}
