import { component$ } from "@qwik.dev/core";
import "./field.css";

export const Footer = component$(() => {
    return (
        <footer class="footer">
            <div class="container">
                <p class="text">
                    &copy; {new Date().getFullYear()} AnyDM. All rights
                    reserved.
                </p>

                <nav class="footer-nav">
                    <a href="/privacy" class="footer-link">
                        Privacy Policy
                    </a>
                    <a href="/terms" class="footer-link">
                        Terms of Service
                    </a>
                </nav>
            </div>
        </footer>
    );
});
