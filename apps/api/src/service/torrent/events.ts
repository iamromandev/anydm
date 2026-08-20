import type { DownloadTask } from "../task";

type Client = {
    id: string;
    controller: ReadableStreamDefaultController<Uint8Array>;
};

const clients = new Set<Client>();
const encoder = new TextEncoder();

function write(client: Client, event: string, data: unknown): void {
    try {
        client.controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
    } catch {
        clients.delete(client);
    }
}

export function broadcast(event: string, data: unknown): void {
    const chunk = encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    for (const client of clients) {
        try {
            client.controller.enqueue(chunk);
        } catch {
            clients.delete(client);
        }
    }
}

export function emitTaskUpdate(task: DownloadTask): void {
    broadcast("task", task);
}

export function emitStatsUpdate(stats: unknown): void {
    broadcast("stats", stats);
}

export function subscribe(): ReadableStream<Uint8Array> {
    let client: Client | undefined;

    const stream = new ReadableStream<Uint8Array>({
        start(controller) {
            client = { id: crypto.randomUUID(), controller };
            controller.enqueue(encoder.encode(": connected\n\n"));
            clients.add(client);
        },
        cancel() {
            if (client) {
                clients.delete(client);
            }
        },
    });

    return stream;
}

export function clientCount(): number {
    return clients.size;
}
