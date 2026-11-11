/**
 * Loads remote control (server, Noise, QR code, the background service) only when `/remote-control` or `pi-bolt service`
 * runs (see entry.ts).
 */
export const loadRemoteControl = () => import("./entry.ts");
