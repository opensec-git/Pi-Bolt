/** Loads remote control (server, Noise, QR code) only when `/remote-control` runs (see controller.ts). */
export const loadRemoteControl = () => import("./controller.ts");
/** Loads `pi-bolt service` only when it runs: the commands (service-cli.ts) and the service itself (service-host.ts). */
export const loadServiceCommand = () => import("./service-cli.ts");
export const loadServiceHost = () => import("./service-host.ts");
