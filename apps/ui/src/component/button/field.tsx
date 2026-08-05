import { component$, type PropsOf, Slot } from "@qwik.dev/core";
import "./field.css";

type ButtonProps = PropsOf<"button"> & {
    label?: string;
    onClick$?: (event: MouseEvent) => void; // made optional
    variant?: "primary" | "secondary" | "outline" | "ghost";
    disabled?: boolean;
    loading?: boolean;
};

export const Button = component$<ButtonProps>(
    ({
        label,
        onClick$,
        variant = "primary",
        disabled,
        loading = false,
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
                        Loading...
                    </span>
                ) : label ? (
                    label
                ) : (
                    <Slot />
                )}
            </button>
        );
    },
);
