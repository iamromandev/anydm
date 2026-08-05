import { component$, isDev } from "@qwik.dev/core";
import { QwikRouterProvider, RouterOutlet } from "@qwik.dev/router";

export default component$(() => {
    return (
        <QwikRouterProvider>
            <head>
                <meta charset="utf-8" />
                <title>AnyDM</title>
            </head>
            <body lang="en">
                <RouterOutlet />
            </body>
        </QwikRouterProvider>
    );
});
