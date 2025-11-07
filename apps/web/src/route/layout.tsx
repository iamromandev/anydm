import { component$, Slot } from "@qwik.dev/core";
import { Header } from "@/component/header";
import { Footer } from "@/component/footer";
import { Menu } from "@/component/menu";

export default component$(() => {
    return (
        <div class="flex flex-col min-h-screen">
            {/* Header */}
            <Header />
            <Menu />
            {/* Main content */}
            <main class="flex-1">
                <Slot />
            </main>

            {/* Footer */}
            <Footer />
        </div>
    );
});
