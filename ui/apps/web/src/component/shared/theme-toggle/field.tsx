import { useEffect, useState } from "react";
import "./field.css";

export function ThemeToggle() {
    const [
        isDark,
        setIsDark,
    ] = useState(false);

    // Read once the page is in a browser: the saved choice, else the system's.
    useEffect(() => {
        const prefersDark = window.matchMedia(
            "(prefers-color-scheme: dark)",
        ).matches;
        const stored = localStorage.getItem("theme");
        const dark = stored === "dark" || (!stored && prefersDark);
        setIsDark(dark);
        applyTheme(dark);
    }, []);

    const toggle = () => {
        const dark = !isDark;
        setIsDark(dark);
        applyTheme(dark);
        localStorage.setItem("theme", dark ? "dark" : "light");
    };

    return (
        <button
            type="button"
            className="theme-toggle"
            onClick={toggle}
            aria-label="Toggle theme"
        >
            {isDark ? (
                <svg
                    xmlns="http://www.w3.org/2000/svg"
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                >
                    <circle cx="12" cy="12" r="5" />
                    <line x1="12" y1="1" x2="12" y2="3" />
                    <line x1="12" y1="21" x2="12" y2="23" />
                    <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                    <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                    <line x1="1" y1="12" x2="3" y2="12" />
                    <line x1="21" y1="12" x2="23" y2="12" />
                    <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                    <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
                </svg>
            ) : (
                <svg
                    xmlns="http://www.w3.org/2000/svg"
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                >
                    <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
                </svg>
            )}
        </button>
    );
}

function applyTheme(dark: boolean) {
    if (dark) {
        document.documentElement.classList.add("dark");
    } else {
        document.documentElement.classList.remove("dark");
    }
}
