import { component$, type PropsOf, Slot } from "@qwik.dev/core";
import "./field.css";

type ButtonProps = Omit<PropsOf<"button">, "children"> & {
    onClick$?: (event: MouseEvent) => void;
    variant?: "primary" | "secondary" | "outline" | "ghost";
    disabled?: boolean;
    loading?: boolean;
    loadingText?: string;
};

export const Button = component$<ButtonProps>(
    ({
        onClick$,
        variant = "primary",
        disabled,
        loading = false,
        loadingText = "Loading...",
        type = "button",
        ...props
    }) => {
        return (
            <button
                type={type}
                disabled={disabled || loading}
                onClick$={onClick$}
                class={`btn ${variant} ${loading ? "loading" : ""}`}
                {...props}
            >
                {loading ? (
                    <span class="flex items-center gap-2">
                        <span class="spinner" />
                        {loadingText}
                    </span>
                ) : (
                    <Slot />
                )}
            </button>
        );
    },
);
