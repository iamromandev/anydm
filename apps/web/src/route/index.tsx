import { component$, $, useSignal, useStore } from "@qwik.dev/core";
import { Button } from "@/component/button";
import { UrlInput } from "@/component/input/url";
import { Info, type VideoInfo } from "@/route/info";
import "../style/global.css";
import "../style/home.css";

type ExtractResponse =
    | { success: true; data: VideoInfo }
    | { success: false; error: string };

export default component$(() => {
    const isLoading = useSignal(false);
    const store = useStore({
        url: "",
        error: "",
        result: null as VideoInfo | null,
    });

    const handleSubmit = $(async (e: Event) => {
        e.preventDefault();

        if (!store.url.trim()) {
            store.error = "URL required";
            store.result = null;
            return;
        }

        isLoading.value = true;
        store.error = "";
        store.result = null;

        try {
            try {
                new URL(store.url.trim());
            } catch {
                store.error = "Invalid URL";
                return;
            }

            const BASE_URL =
                import.meta.env.PUBLIC_BASE_URL ||
                (import.meta.env.DEV ? "http://localhost:3000" : "");

            if (!BASE_URL) {
                throw new Error(
                    "PUBLIC_BASE_URL is missing. Copy apps/web/.env.example to apps/web/.env.local and restart the dev server.",
                );
            }

            const response = await fetch(`${BASE_URL}/extract`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({ url: store.url.trim() }),
            });

            const payload = (await response.json()) as ExtractResponse;

            if (!response.ok || !payload.success) {
                store.error =
                    payload.success === false
                        ? payload.error
                        : "Failed to extract video info";
                return;
            }

            store.result = payload.data;
        } catch (err: unknown) {
            console.error(err);
            store.error =
                err instanceof Error ? err.message : "Please enter a valid URL";
        } finally {
            isLoading.value = false;
        }
    });

    return (
        <div class="home-container">
            <div class="home-box">
                <div class="home-header">
                    <h1>Any Download Manager</h1>
                    <p>Paste a YouTube link to extract video details.</p>
                </div>

                <form
                    preventdefault:submit
                    onSubmit$={handleSubmit}
                    class="home-form"
                >
                    <UrlInput
                        value={store.url}
                        onChange$={(v: any) => {
                            store.url = v ?? "";
                            store.error = "";
                        }}
                        placeholder="https://www.youtube.com/watch?v=..."
                        error={store.error}
                        required
                    />

                    <Button type="submit" disabled={isLoading.value}>
                        {isLoading.value ? "Extracting..." : "Extract"}
                    </Button>
                </form>

                {store.result && (
                    <Info data={store.result} videoUrl={store.url} />
                )}
            </div>
        </div>
    );
});
