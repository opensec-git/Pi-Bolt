/**
 * Remote control as it loads on first use (remote-control.lazy.ts): `/remote-control` in the terminal and the
 * `pi-bolt service` commands. Everything here stays off Pi's startup.
 */

export { RemoteControlController } from "./controller.ts";
export { runServiceCommand } from "./service-cli.ts";
export { runServiceHost } from "./service-host.ts";
