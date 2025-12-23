import { Config } from "./types";

declare var _getImportsFunctionName: string;
declare var _runtimeName: string;
export const runtimeName = _runtimeName;

let remoteImports: any = null;
try {
    remoteImports = require(_runtimeName + "_core/DeviceBridge")?.[_getImportsFunctionName]?.();
} catch {
    remoteImports = null;
}

function callRemoteFunction(method: string, ...args: any[]): any | null {
    try {
        const fn = remoteImports?.[method];
        if (typeof fn !== "function") return null;
        return fn(...args);
    } catch {
        return null;
    }
}


export const log = (logLevel: string, message: string) => callRemoteFunction("log", logLevel, message);

export const getConfig = () => callRemoteFunction("getConfig") as Config;

export const downloadLastOperaMedia = (isLongPress: boolean) => callRemoteFunction("downloadLastOperaMedia", isLongPress);

export function getFriendOriginalUsername(username: string): string | null {
    return callRemoteFunction("getFriendOriginalUsername", username);
}