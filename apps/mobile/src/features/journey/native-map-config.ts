import { nativeApiOrigin } from '../../auth/safe-transport';

export function nativeMapStyle(
  originValue: string | undefined,
  path: string | undefined,
): string | null {
  const origin = nativeApiOrigin(originValue);
  if (!origin || !path || !/^\/maps\/[a-z0-9/_-]+\.json$/.test(path) || path.includes('//'))
    return null;
  return origin + path;
}
