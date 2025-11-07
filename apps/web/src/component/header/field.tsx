import { component$ } from "@qwik.dev/core";
import "./field.css";

export const Header = component$(() => {
    return (
        <header class="header">
            <div class="container">
                <h1 class="logo">AnyDM</h1>
                <nav class="nav">
                    <a class="nav-link" href="/">
                        Home
                    </a>
                    <a class="nav-link" href="/about">
                        About
                    </a>
                    <a class="nav-link" href="/contact">
                        Contact
                    </a>
                </nav>
            </div>
        </header>
    );
});
