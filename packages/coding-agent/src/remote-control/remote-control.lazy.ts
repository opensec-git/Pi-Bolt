/** Loads remote control (server, Noise, QR code) only when `/remote-control` runs (see controller.ts). */
export const loadRemoteControl = () => import("./controller.ts");
