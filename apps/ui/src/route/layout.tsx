import { component$, Slot } from "@qwik.dev/core";
import { Header } from "@/component/header";
import { Footer } from "@/component/footer";

export default component$(() => {
    return (
        <div class="flex flex-col min-h-screen">
            <Header />
            <main class="flex-1">
                <Slot />
            </main>
            <Footer />
        </div>
    );
});
