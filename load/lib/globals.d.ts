// k6 provides a global console; @types/k6 does not declare it, and the DOM library would bring in much more.
declare const console: {
    log(...data: unknown[]): void;
    warn(...data: unknown[]): void;
    error(...data: unknown[]): void;
};
