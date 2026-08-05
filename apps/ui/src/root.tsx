import { component$, isDev } from "@qwik.dev/core";
import { QwikRouterProvider, RouterOutlet } from "@qwik.dev/router";

export default component$(() => {
    return (
        <QwikRouterProvider>
            <head>
                <meta charset="utf-8" />
                <title>AnyDM</title>
                <link rel="preconnect" href="https://fonts.googleapis.com" />
                <link
                    rel="preconnect"
                    href="https://fonts.gstatic.com"
                    crossOrigin="anonymous"
                />
                <link
                    href="https://fonts.googleapis.com/css2?family=Satoshi:wght@300;400;500;600;700&display=swap"
                    rel="stylesheet"
                />
            </head>
            <body lang="en">
                <RouterOutlet />
            </body>
        </QwikRouterProvider>
    );
});
