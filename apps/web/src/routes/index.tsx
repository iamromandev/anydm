import { component$, $, useSignal, useStore } from "@qwik.dev/core";
import { useNavigate } from "@qwik.dev/router";
import { Button } from "@/components/button";
import { UrlInput } from "@/components/input/url";

export default component$(() => {
    const nav = useNavigate();
    const isLoading = useSignal(false);
    const store = useStore({
        url: "",
        error: "",
    });

    const handleSubmit = $(async (e: Event) => {
        e.preventDefault();
        if (!store.url.trim()) return;

        isLoading.value = true;
        try {
            // Basic URL validation
            new URL(store.url);
            await nav(`/analyze?url=${encodeURIComponent(store.url)}`);
        } catch {
            store.error = "Please enter a valid URL with http:// or https://";
        } finally {
            isLoading.value = false;
        }
    });

    const handleKeyPress = $((e: KeyboardEvent) => {
        if (e.key === "Enter") handleSubmit(e);
    });

    return (
        <div class="flex min-h-screen items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-100 p-4">
            <div class="w-full max-w-md space-y-8">
                {/* Header */}
                <div class="text-center">
                    <h1 class="mb-2 text-3xl font-bold text-gray-900">
                        URL Analyzer
                    </h1>
                    <p class="text-gray-600">
                        Enter a URL to analyze website performance and SEO
                    </p>
                </div>

                {/* URL Form */}
                <form onSubmit$={handleSubmit} class="space-y-4">
                    <UrlInput
                        value={store.url}
                        onChange$={(value: any) => (store.url = value ?? "")}
                        onKeyPress$={handleKeyPress}
                        placeholder="Paste your file URL here..."
                        error={store.error}
                        required
                    />

                    <Button
                        type="submit"
                        disabled={isLoading.value}
                        class="w-full rounded-lg bg-blue-600 py-3 font-semibold text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
                    >
                        {isLoading.value ? "Analyzing..." : "Analyze URL"}
                    </Button>
                </form>
            </div>
        </div>
    );
});
