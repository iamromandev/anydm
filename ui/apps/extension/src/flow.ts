import type { AddOutcome } from "./bridge";
import type { BridgeApi, Connected } from "./client";
import { withoutStrictCrossSite } from "./cookies";
import { describeFailure, type FailureAction } from "./messages";
import { buildAddBody, checkUrl, type BrowserCookie } from "./request";
import { DEFAULT_SETTINGS, skipReason, type Settings } from "./settings";

/** How long the app keeps what it read, before the link must be read again. */
export const TOKEN_LIFE_MS = 15_000;

/** The link the user picked, and the page it was on. */
export interface Target {
    url: string;
    pageUrl?: string;
    tabId?: number;
    /**
     * The download was taken over by itself, not asked for. Only these are left to the browser when the user's settings say
     * so; a link the user sent on purpose (the context menu) is always sent.
     */
    automatic?: boolean;
}

export type State =
    | { step: "finding" }
    | { step: "needs-permission" }
    | {
          step: "ready";
          name: string | null;
          size: number | null;
          type: string;
          rangesSupported: boolean;
          /** The cookies were too many to send and are left out. */
          droppedCookies: boolean;
          /** This browser is paired with the app and the app proved it: cookies go with the link. Otherwise none do. */
          verified: boolean;
          /** The user chose to add without cookies. */
          withoutCookies: boolean;
      }
    | { step: "duplicate"; name: string | null }
    | { step: "skipped"; reason: "excluded" | "small" }
    | { step: "adding" }
    | { step: "added" }
    | { step: "expired" }
    | { step: "failed"; text: string; action: FailureAction };

/** What the flow needs from the browser, so tests can stand in for it. */
export interface Deps {
    /** The bridge to the app: the session's owner, or a page that asks it. */
    bridge: BridgeApi;
    userAgent: string;
    /** The user's skip settings; when it fails or is absent nothing is skipped. */
    settings?(): Promise<Settings>;
    hasHostAccess(): Promise<boolean>;
    requestHostAccess(): Promise<boolean>;
    cookiesFor(url: string, tabId?: number): Promise<BrowserCookie[]>;
    lastPort: { get(): number | undefined; set(port: number): void };
    setTimer(callback: () => void, ms: number): unknown;
    clearTimer(handle: unknown): void;
}

/**
 * Adding one link (OSI-04): find anydm, read the link with the browser's cookies, show what was found, and add it when the
 * user says so. The state changes are reported to [onChange]; a flow can be started again to read the link anew, which
 * reads the cookies from the browser again, so nothing about the page is kept.
 */
export class Flow {
    state: State = { step: "finding" };
    private run = 0;
    private expiry: unknown;
    private token: string | undefined;
    // The port this flow found anydm on: what it adds to, whatever the remembered port says by then.
    private port: number | undefined;
    private confirming = false;

    constructor(
        private readonly deps: Deps,
        private readonly target: Target,
        private readonly onChange: (state: State) => void,
    ) {}

    /** The port this flow found anydm on, once it has. */
    get foundPort(): number | undefined {
        return this.port;
    }

    /** Lets go of what the flow holds (its timer, the token) and ends any read still going. Used when nobody will look again. */
    close() {
        this.run += 1;
        this.token = undefined;
        this.clearExpiry();
    }

    /** Finds anydm and reads the link. */
    start(): Promise<void> {
        return this.read(false);
    }

    /** Reads the link again without the cookies, after the app refused them. */
    withoutCookies(): Promise<void> {
        return this.read(true);
    }

    /** Asks the browser for the access it needs, then starts. */
    async allowAccess(): Promise<void> {
        const granted = await this.deps.requestHostAccess();
        if (granted) await this.start();
    }

    /** Adds the link that was read. A second call while one is going, or with nothing read, does nothing. */
    async confirm(): Promise<void> {
        const token = this.token;
        if (
            this.state.step !== "ready" ||
            token === undefined ||
            this.confirming
        ) {
            return;
        }
        const port = this.port;
        if (port === undefined) return;
        this.confirming = true;
        this.token = undefined;
        this.clearExpiry();
        this.set({ step: "adding" });
        try {
            const outcome = await this.deps.bridge.confirm(port, token);
            if (outcome.kind === "added") {
                this.set({ step: "added" });
            } else {
                const failure = describeFailure(outcome, "confirm");
                this.set({ step: "failed", ...failure });
            }
        } finally {
            this.confirming = false;
        }
    }

    private async read(withoutCookies: boolean): Promise<void> {
        const run = ++this.run;
        const current = () => run === this.run;
        this.token = undefined;
        this.clearExpiry();
        this.set({ step: "finding" });

        const url = checkUrl(this.target.url);
        if (url !== "ok") {
            this.set({
                step: "failed",
                text:
                    url === "too-long"
                        ? "This address is too long for anydm."
                        : "anydm can't take this kind of link.",
                action: undefined,
            });
            return;
        }
        const settings = await this.settings();
        if (!current()) return;
        if (
            settings &&
            skipReason(settings, this.target.url, null) === "excluded"
        ) {
            this.set({ step: "skipped", reason: "excluded" });
            return;
        }
        if (!(await this.deps.hasHostAccess())) {
            if (current()) this.set({ step: "needs-permission" });
            return;
        }
        const found = await this.deps.bridge.connect(this.deps.lastPort.get());
        if (!current()) return;
        const refusal = this.refusal(found);
        if (refusal) return this.fail(refusal);
        if (found.kind !== "ready") return;
        this.port = found.port;
        this.deps.lastPort.set(found.port);

        // Cookies are read, and sent, only to an app that proved it is the one this browser is paired with.
        const read =
            found.verified && !withoutCookies
                ? await this.cookies()
                : { cookies: [], failed: false };
        if (!current()) return;
        const built = buildAddBody({
            url: this.target.url,
            pageUrl: this.target.pageUrl,
            userAgent: this.deps.userAgent,
            cookies: read.cookies,
        });
        const outcome: AddOutcome = await this.deps.bridge.add(
            found.port,
            built.body,
        );
        if (!current()) return;
        if (outcome.kind !== "probed") {
            return this.fail(describeFailure(outcome, "add"), withoutCookies);
        }
        const skip = settings
            ? skipReason(settings, this.target.url, outcome.size)
            : null;
        if (skip) {
            this.set({ step: "skipped", reason: skip });
            return;
        }
        if (outcome.duplicate) {
            this.set({ step: "duplicate", name: outcome.name });
            return;
        }
        this.token = outcome.token;
        this.expiry = this.deps.setTimer(() => {
            if (this.state.step === "ready") {
                this.token = undefined;
                this.set({ step: "expired" });
            }
        }, TOKEN_LIFE_MS);
        this.set({
            step: "ready",
            name: outcome.name,
            size: outcome.size,
            type: outcome.type,
            rangesSupported: outcome.rangesSupported,
            // Cookies that could not be read are cookies that were left out, as far as a capture is concerned: the link may
            // need them, so the browser keeps its copy. (The menu send never made a point of it: the person sees the file.)
            droppedCookies:
                built.droppedCookies ||
                (read.failed && this.target.automatic === true),
            verified: found.verified,
            withoutCookies,
        });
    }

    // Only an automatic download is checked against the settings; a failing store means nothing is skipped.
    private async settings(): Promise<Settings | null> {
        if (!this.target.automatic || !this.deps.settings) return null;
        try {
            return await this.deps.settings();
        } catch {
            return DEFAULT_SETTINGS;
        }
    }

    // Only a web address has cookies, and a browser that can't read them is no reason not to add the link ([failed] says it
    // could not). A download that took itself over is sent what its page could have sent: no SameSite=Strict cookie from
    // another site.
    private async cookies(): Promise<{
        cookies: BrowserCookie[];
        failed: boolean;
    }> {
        if (!/^https?:/i.test(this.target.url)) {
            return { cookies: [], failed: false };
        }
        try {
            const cookies = await this.deps.cookiesFor(
                this.target.url,
                this.target.tabId,
            );
            return {
                cookies: this.target.automatic
                    ? withoutStrictCrossSite(
                          cookies,
                          this.target.url,
                          this.target.pageUrl,
                      )
                    : cookies,
                failed: false,
            };
        } catch {
            return { cookies: [], failed: true };
        }
    }

    // Why this flow can't go on with what [found] came to, or null when it can.
    private refusal(
        found: Connected,
    ): { text: string; action: FailureAction } | null {
        switch (found.kind) {
            case "none":
                return describeFailure({ kind: "unreachable" }, "add");
            case "forbidden":
                return describeFailure(
                    {
                        kind: "refused",
                        status: 403,
                        error: "forbidden",
                        message: "",
                    },
                    "add",
                );
            case "unverified":
                return describeFailure(
                    {
                        kind: "refused",
                        ...(found.reason === "unavailable"
                            ? { status: 503, error: "store_unavailable" }
                            : found.reason === "unpaired"
                              ? { status: 401, error: "unpaired" }
                              : { status: 401, error: "unverified" }),
                        message: "",
                    },
                    "add",
                );
            case "no-background":
                return {
                    text: "The extension's background part did not answer. Reload the extension and try again.",
                    action: "retry",
                };
            case "ready":
                // A download taken over by itself goes only to an app this browser is paired with and has verified.
                return this.target.automatic && !found.verified
                    ? {
                          text: "Capturing downloads needs this browser to be paired with anydm.",
                          action: undefined,
                      }
                    : null;
        }
    }

    private fail(
        failure: { text: string; action: FailureAction },
        triedWithoutCookies = false,
    ) {
        // Offering "without cookies" again after it was tried would loop.
        const action =
            triedWithoutCookies && failure.action === "without-cookies"
                ? undefined
                : failure.action;
        this.set({ step: "failed", text: failure.text, action });
    }

    private clearExpiry() {
        if (this.expiry !== undefined) this.deps.clearTimer(this.expiry);
        this.expiry = undefined;
    }

    private set(state: State) {
        this.state = state;
        this.onChange(state);
    }
}
