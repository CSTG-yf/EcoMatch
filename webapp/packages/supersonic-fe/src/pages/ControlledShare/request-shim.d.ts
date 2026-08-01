declare const request: <T = any>(url: string, options?: Record<string, unknown>) => Promise<T>;

export const TOKEN_KEY: string;
export default request;
