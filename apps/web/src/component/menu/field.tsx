import { component$, useSignal } from "@qwik.dev/core";
import "./field.css";

export const Menu = component$(() => {
    const isOpen = useSignal(false);

    return (
        <nav class="menu">
            {/* Mobile toggle */}
            <button
                class="menu-toggle"
                onClick$={() => (isOpen.value = !isOpen.value)}
                aria-label="Toggle menu"
            >
                <svg
                    xmlns="http://www.w3.org/2000/svg"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke-width="2"
                    stroke="currentColor"
                    class="h-6 w-6"
                >
                    {isOpen.value ? (
                        <path
                            stroke-linecap="round"
                            stroke-linejoin="round"
                            d="M6 18L18 6M6 6l12 12"
                        />
                    ) : (
                        <path
                            stroke-linecap="round"
                            stroke-linejoin="round"
                            d="M4 6h16M4 12h16M4 18h16"
                        />
                    )}
                </svg>
            </button>

            {/* Links */}
            <ul class={`menu-list ${isOpen.value ? "open" : ""}`}>
                <li>
                    <a href="/" class="menu-link">
                        Home
                    </a>
                </li>
                <li>
                    <a href="/features" class="menu-link">
                        Features
                    </a>
                </li>
                <li>
                    <a href="/pricing" class="menu-link">
                        Pricing
                    </a>
                </li>
                <li>
                    <a href="/about" class="menu-link">
                        About
                    </a>
                </li>
                <li>
                    <a href="/contact" class="menu-link">
                        Contact
                    </a>
                </li>
            </ul>
        </nav>
    );
});
