import { createBrowserRouter } from "react-router-dom";
import { Layout } from "@/route/layout";
import { HomePage } from "@/route/home-page";

export const router = createBrowserRouter([
    {
        path: "/",
        element: <Layout />,
        children: [
            { index: true, element: <HomePage /> },
        ],
    },
]);
