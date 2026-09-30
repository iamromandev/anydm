import { component$ } from "@qwik.dev/core";
import { doorsFor } from "./present";
import "./field.css";

export interface StartDoorsProps {
    /** Browse latest is a torrent list, so the door is there only when there are torrent sources. */
    torrentSearch: boolean;
    onBrowse: () => void;
}

/** The ways to start, for a list with nothing in it yet. */
export const StartDoors = component$<StartDoorsProps>(
    ({ torrentSearch, onBrowse }) => (
        <div class="start-doors">
            {doorsFor(torrentSearch).map((door) => {
                const body = (
                    <>
                        <strong>{door.title}</strong>
                        <span>{door.hint}</span>
                    </>
                );
                if (!door.action) {
                    return (
                        <p key={door.id} class="start-door start-door--hint">
                            {body}
                        </p>
                    );
                }
                return (
                    <button
                        key={door.id}
                        type="button"
                        class="start-door"
                        onClick$={() => {
                            if (door.id === "browse") {
                                onBrowse();
                                return;
                            }
                            document
                                .querySelector<HTMLInputElement>(
                                    ".hero-input-field",
                                )
                                ?.focus();
                        }}
                    >
                        {body}
                    </button>
                );
            })}
        </div>
    ),
);
