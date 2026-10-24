import {
    addLink,
    confirmLink,
    findBridge,
    type AddOutcome,
    type Fetch,
} from "./bridge";
import { describeFailure, type FailureAction } from "./messages";
import { buildAddBody, checkUrl, type BrowserCookie } from "./request";

/** How long the app keeps what it read, before the link must be read again. */
export const TOKEN_LIFE_MS = 15_000;

/** The link the user picked, and the page it was on. */
export interface Target {
    url: string;
    pageUrl?: string;
    tabId?: number;
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
          /** The user chose to add without cookies. */
          withoutCookies: boolean;
      }
    | { step: "duplicate"; name: string | null }
    | { step: "adding" }
    | { step: "added" }
    | { step: "expired" }
    | { step: "failed"; text: string; action: FailureAction };

/** What the flow needs from the browser, so tests can stand in for it. */
export interface Deps {
    fetch: Fetch;
    userAgent: string;
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
            const outcome = await confirmLink(this.deps.fetch, port, token);
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
        if (!(await this.deps.hasHostAccess())) {
            if (current()) this.set({ step: "needs-permission" });
            return;
        }
        const found = await findBridge(
            this.deps.fetch,
            this.deps.lastPort.get(),
        );
        if (!current()) return;
        if (found === null) {
            return this.fail(describeFailure({ kind: "unreachable" }, "add"));
        }
        if (!found.allowed) {
            return this.fail(
                describeFailure(
                    {
                        kind: "refused",
                        status: 403,
                        error: "forbidden",
                        message: "",
                    },
                    "add",
                ),
            );
        }
        this.port = found.port;
        this.deps.lastPort.set(found.port);

        const cookies = withoutCookies ? [] : await this.cookies();
        if (!current()) return;
        const built = buildAddBody({
            url: this.target.url,
            pageUrl: this.target.pageUrl,
            userAgent: this.deps.userAgent,
            cookies,
        });
        const outcome: AddOutcome = await addLink(
            this.deps.fetch,
            found.port,
            built.body,
        );
        if (!current()) return;
        if (outcome.kind !== "probed") {
            return this.fail(describeFailure(outcome, "add"), withoutCookies);
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
            droppedCookies: built.droppedCookies,
            withoutCookies,
        });
    }

    // Only a web address has cookies, and a browser that can't read them is no reason not to add the link.
    private async cookies(): Promise<BrowserCookie[]> {
        if (!/^https?:/i.test(this.target.url)) return [];
        try {
            return await this.deps.cookiesFor(
                this.target.url,
                this.target.tabId,
            );
        } catch {
            return [];
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
